import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from typing import Any, Dict, List, Optional

import joblib
import jwt
import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from features import (
    DEFAULT_MEDIANS,
    FEATURE_COLS,
    compute_single_features,
    generate_reasons,
    validate_vitals,
)

MODEL_PATH = "wardpulse_model.joblib"
DB_PATH = "wardpulse.db"
JWT_SECRET = os.environ.get("WARDPULSE_JWT_SECRET", "wardpulse-hackathon-secret-key-2026")
JWT_ALGORITHM = "HS256"
TOKEN_TTL_SECONDS = 7 * 24 * 3600  # 7 days

app = FastAPI(title="WardPulse Multi-Hospital Sync API", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

MODEL_BUNDLE: Optional[Dict[str, Any]] = None
if os.path.exists(MODEL_PATH):
    MODEL_BUNDLE = joblib.load(MODEL_PATH)

TIER_PRIORITY = {
    "RED": 0,
    "YELLOW": 1,
    "UNCERTAIN": 2,
    "GREEN": 3,
}


# ==============================================================================
# DATABASE SETUP (SQLite — Zero External Dependencies)
# ==============================================================================
def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    conn = get_db()
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS hospitals (
            hospital_id TEXT PRIMARY KEY,
            hospital_name TEXT NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS patients (
            hospital_id TEXT NOT NULL,
            patient_id TEXT NOT NULL,
            name TEXT NOT NULL,
            ward TEXT NOT NULL,
            bed TEXT NOT NULL,
            age REAL NOT NULL,
            hr REAL NOT NULL,
            sbp REAL NOT NULL,
            dbp REAL NOT NULL,
            temp REAL NOT NULL,
            resp REAL NOT NULL,
            o2sat REAL NOT NULL,
            risk_score REAL NOT NULL,
            risk_tier TEXT NOT NULL,
            estimated_hours_to_onset REAL NOT NULL,
            recheck_interval_mins INTEGER NOT NULL,
            last_checked_epoch INTEGER NOT NULL,
            updated_at_epoch INTEGER NOT NULL,
            shock_index REAL NOT NULL,
            map_val REAL NOT NULL,
            top_reasons_json TEXT NOT NULL,
            recommended_action TEXT NOT NULL,
            PRIMARY KEY (hospital_id, patient_id),
            FOREIGN KEY (hospital_id) REFERENCES hospitals(hospital_id)
        )
        """
    )
    conn.commit()
    conn.close()


init_db()


# ==============================================================================
# PASSWORD HASHING & JWT AUTH HELPERS
# ==============================================================================
def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000)
    return f"{salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        salt, hex_hash = stored.split("$", 1)
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000)
        return hmac.compare_digest(dk.hex(), hex_hash)
    except Exception:
        return False


def create_access_token(hospital_id: str, hospital_name: str, email: str) -> str:
    now = int(time.time())
    payload = {
        "sub": hospital_id,
        "hospital_name": hospital_name,
        "email": email,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def get_current_hospital(authorization: Optional[str] = Header(default=None)) -> Dict[str, str]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Authorization header. Expected 'Bearer <token>'.",
        )
    token = authorization.split(" ", 1)[1].strip()
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return {
            "hospital_id": payload["sub"],
            "hospital_name": payload["hospital_name"],
            "email": payload["email"],
        }
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session expired. Please log in again.")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid authentication token.")


# ==============================================================================
# PYDANTIC SCHEMAS (KOTLIN RETROFIT CONTRACT)
# ==============================================================================
class RegisterRequest(BaseModel):
    hospital_name: str = Field(..., min_length=2, example="Apollo General Hospital - Secunderabad")
    email: str = Field(..., example="nurse.station@apollo-hyd.in")
    password: str = Field(..., min_length=4, example="wardpulse123")
    seed_demo_patients: bool = Field(default=True)


class LoginRequest(BaseModel):
    email: str = Field(..., example="nurse.station@apollo-hyd.in")
    password: str = Field(..., example="wardpulse123")


class AuthResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    hospital_id: str
    hospital_name: str
    email: str


class VitalsRequest(BaseModel):
    patient_id: str
    name: str
    ward: str
    bed: str
    age: float
    hr: float
    sbp: float
    dbp: float
    temp: float
    resp: float
    o2sat: float
    client_checked_epoch: Optional[int] = None  # Supports offline Kotlin timestamps during sync


class BatchSyncRequest(BaseModel):
    patients: List[VitalsRequest]


class BulkDeleteRequest(BaseModel):
    patient_ids: List[str]


class PatientResponse(BaseModel):
    patient_id: str
    name: str
    ward: str
    bed: str
    age: float
    hr: float
    sbp: float
    dbp: float
    temp: float
    resp: float
    o2sat: float
    risk_score: float
    risk_tier: str
    estimated_hours_to_onset: float
    recheck_interval_mins: int
    last_checked_epoch: int
    updated_at_epoch: int
    shock_index: float
    map: float
    top_reasons: List[str]
    recommended_action: str


# ==============================================================================
# ML INFERENCE & TRIAGE ENGINE
# ==============================================================================
def run_inference(req: VitalsRequest, override_epoch: Optional[int] = None) -> PatientResponse:
    now = int(time.time())
    checked_ts = override_epoch or req.client_checked_epoch or now
    vitals_dict = req.model_dump()

    safe_sbp = max(float(req.sbp), 1.0)
    shock_index = round(float(req.hr) / safe_sbp, 2)
    map_val = round(float(req.dbp) + (float(req.sbp) - float(req.dbp)) / 3.0, 1)

    if not validate_vitals(req.hr, req.sbp, req.dbp, req.temp, req.resp, req.o2sat):
        return PatientResponse(
            patient_id=req.patient_id,
            name=req.name,
            ward=req.ward,
            bed=req.bed,
            age=req.age,
            hr=req.hr,
            sbp=req.sbp,
            dbp=req.dbp,
            temp=req.temp,
            resp=req.resp,
            o2sat=req.o2sat,
            risk_score=0.0,
            risk_tier="UNCERTAIN",
            estimated_hours_to_onset=6.0,
            recheck_interval_mins=15,
            last_checked_epoch=checked_ts,
            updated_at_epoch=now,
            shock_index=shock_index,
            map=map_val,
            top_reasons=["Physiological range check failed: reading outside valid bedside bounds"],
            recommended_action="Invalid reading detected. Re-check cuff/oximeter and re-enter vitals.",
        )

    if MODEL_BUNDLE is not None:
        medians = MODEL_BUNDLE.get("medians", DEFAULT_MEDIANS)
        feature_cols = MODEL_BUNDLE.get("feature_cols", FEATURE_COLS)
        X_row = compute_single_features(vitals_dict, medians=medians)[feature_cols]
        risk_score = float(MODEL_BUNDLE["classifier"].predict_proba(X_row)[0, 1])
        raw_hours = float(MODEL_BUNDLE["regressor"].predict(X_row)[0])
    else:
        si = float(req.hr) / safe_sbp
        mp = float(req.dbp) + (float(req.sbp) - float(req.dbp)) / 3.0
        z = (
            -3.8
            + 4.2 * max(0.0, si - 0.65)
            + 0.05 * max(0.0, 75.0 - mp)
            + 0.14 * max(0.0, float(req.resp) - 18.0)
            + 0.22 * max(0.0, 96.0 - float(req.o2sat))
            + 0.35 * max(0.0, float(req.temp) - 37.6)
        )
        risk_score = float(1.0 / (1.0 + np.exp(-z)))
        raw_hours = 6.0 - (risk_score * 5.2)

    risk_score = round(float(np.clip(risk_score, 0.0, 1.0)), 2)
    est_hours = round(float(np.clip(raw_hours, 0.5, 6.0)), 1)

    if risk_score >= 0.97 and est_hours <= 3.5:
        risk_tier = "RED"
        recheck_mins = 30
        action = "URGENT: Call duty doctor now. Re-check vitals in 30 mins."
    elif risk_score >= 0.40:
        risk_tier = "YELLOW"
        recheck_mins = 60
        action = "WATCHLIST: Monitor closely and repeat vitals check in 60 mins."
    else:
        risk_tier = "GREEN"
        recheck_mins = 240
        est_hours = 6.0
        action = "STABLE: Continue routine 4-hour ward checkup schedule."

    reasons = generate_reasons(vitals_dict, shock_index, map_val, est_hours)

    return PatientResponse(
        patient_id=req.patient_id,
        name=req.name,
        ward=req.ward,
        bed=req.bed,
        age=req.age,
        hr=req.hr,
        sbp=req.sbp,
        dbp=req.dbp,
        temp=req.temp,
        resp=req.resp,
        o2sat=req.o2sat,
        risk_score=risk_score,
        risk_tier=risk_tier,
        estimated_hours_to_onset=est_hours,
        recheck_interval_mins=recheck_mins,
        last_checked_epoch=checked_ts,
        updated_at_epoch=now,
        shock_index=shock_index,
        map=map_val,
        top_reasons=reasons,
        recommended_action=action,
    )


def upsert_patient_db(conn: sqlite3.Connection, hospital_id: str, p: PatientResponse) -> None:
    conn.execute(
        """
        INSERT INTO patients (
            hospital_id, patient_id, name, ward, bed, age, hr, sbp, dbp, temp, resp, o2sat,
            risk_score, risk_tier, estimated_hours_to_onset, recheck_interval_mins,
            last_checked_epoch, updated_at_epoch, shock_index, map_val, top_reasons_json, recommended_action
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(hospital_id, patient_id) DO UPDATE SET
            name=excluded.name,
            ward=excluded.ward,
            bed=excluded.bed,
            age=excluded.age,
            hr=excluded.hr,
            sbp=excluded.sbp,
            dbp=excluded.dbp,
            temp=excluded.temp,
            resp=excluded.resp,
            o2sat=excluded.o2sat,
            risk_score=excluded.risk_score,
            risk_tier=excluded.risk_tier,
            estimated_hours_to_onset=excluded.estimated_hours_to_onset,
            recheck_interval_mins=excluded.recheck_interval_mins,
            last_checked_epoch=excluded.last_checked_epoch,
            updated_at_epoch=excluded.updated_at_epoch,
            shock_index=excluded.shock_index,
            map_val=excluded.map_val,
            top_reasons_json=excluded.top_reasons_json,
            recommended_action=excluded.recommended_action
        """,
        (
            hospital_id,
            p.patient_id,
            p.name,
            p.ward,
            p.bed,
            p.age,
            p.hr,
            p.sbp,
            p.dbp,
            p.temp,
            p.resp,
            p.o2sat,
            p.risk_score,
            p.risk_tier,
            p.estimated_hours_to_onset,
            p.recheck_interval_mins,
            p.last_checked_epoch,
            p.updated_at_epoch,
            p.shock_index,
            p.map,
            json.dumps(p.top_reasons),
            p.recommended_action,
        ),
    )


def row_to_patient_response(row: sqlite3.Row) -> PatientResponse:
    return PatientResponse(
        patient_id=row["patient_id"],
        name=row["name"],
        ward=row["ward"],
        bed=row["bed"],
        age=row["age"],
        hr=row["hr"],
        sbp=row["sbp"],
        dbp=row["dbp"],
        temp=row["temp"],
        resp=row["resp"],
        o2sat=row["o2sat"],
        risk_score=row["risk_score"],
        risk_tier=row["risk_tier"],
        estimated_hours_to_onset=row["estimated_hours_to_onset"],
        recheck_interval_mins=row["recheck_interval_mins"],
        last_checked_epoch=row["last_checked_epoch"],
        updated_at_epoch=row["updated_at_epoch"],
        shock_index=row["shock_index"],
        map=row["map_val"],
        top_reasons=json.loads(row["top_reasons_json"]),
        recommended_action=row["recommended_action"],
    )


def seed_hospital_demo_patients(conn: sqlite3.Connection, hospital_id: str) -> None:
    now = int(time.time())
    conn.execute("DELETE FROM patients WHERE hospital_id = ?", (hospital_id,))

    demo_cases = [
        (
            VitalsRequest(
                patient_id="P04", name="Ramesh K.", ward="General Ward A", bed="Bed 4",
                age=58, hr=118, sbp=88, dbp=54, temp=38.6, resp=26, o2sat=91
            ),
            now - 300,
        ),
        (
            VitalsRequest(
                patient_id="P12", name="Meena R.", ward="Post-Op Ward", bed="Bed 2",
                age=64, hr=106, sbp=96, dbp=60, temp=38.1, resp=23, o2sat=94
            ),
            now - 600,
        ),
        (
            VitalsRequest(
                patient_id="P08", name="Suresh V.", ward="General Ward B", bed="Bed 1",
                age=52, hr=90, sbp=112, dbp=70, temp=37.8, resp=20, o2sat=96
            ),
            now - 1200,
        ),
        (
            VitalsRequest(
                patient_id="P15", name="Kavitha N.", ward="Post-Op Ward", bed="Bed 5",
                age=47, hr=89, sbp=114, dbp=72, temp=37.7, resp=19, o2sat=96
            ),
            now - 1800,
        ),
        # Overdue by 4 hours 15 mins (15,300s) so Kotlin 4-hour checkup alarm triggers immediately
        (
            VitalsRequest(
                patient_id="P02", name="Anil G.", ward="General Ward A", bed="Bed 1",
                age=41, hr=76, sbp=120, dbp=78, temp=36.8, resp=16, o2sat=98
            ),
            now - (4 * 3600 + 15 * 60),
        ),
        (
            VitalsRequest(
                patient_id="P10", name="Padma S.", ward="General Ward B", bed="Bed 3",
                age=36, hr=74, sbp=118, dbp=74, temp=36.7, resp=15, o2sat=99
            ),
            now - 900,
        ),
    ]

    for req, ts in demo_cases:
        pred = run_inference(req, override_epoch=ts)
        upsert_patient_db(conn, hospital_id, pred)


# ==============================================================================
# AUTH ENDPOINTS
# ==============================================================================
@app.get("/health")
def health_check() -> Dict[str, Any]:
    return {
        "status": "ok",
        "model_loaded": MODEL_BUNDLE is not None,
        "server_epoch": int(time.time()),
    }


@app.post("/auth/register", response_model=AuthResponse, status_code=status.HTTP_201_CREATED)
def register_hospital(req: RegisterRequest) -> AuthResponse:
    email_clean = req.email.strip().lower()
    conn = get_db()
    try:
        existing = conn.execute("SELECT hospital_id FROM hospitals WHERE email = ?", (email_clean,)).fetchone()
        if existing:
            raise HTTPException(status_code=400, detail="An account with this email already exists.")

        hospital_id = f"HOSP_{secrets.token_hex(4).upper()}"
        now = int(time.time())
        conn.execute(
            "INSERT INTO hospitals (hospital_id, hospital_name, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            (hospital_id, req.hospital_name.strip(), email_clean, hash_password(req.password), now),
        )

        if req.seed_demo_patients:
            seed_hospital_demo_patients(conn, hospital_id)

        conn.commit()
        token = create_access_token(hospital_id, req.hospital_name.strip(), email_clean)
        return AuthResponse(
            access_token=token,
            hospital_id=hospital_id,
            hospital_name=req.hospital_name.strip(),
            email=email_clean,
        )
    finally:
        conn.close()


@app.post("/auth/login", response_model=AuthResponse)
def login_hospital(req: LoginRequest) -> AuthResponse:
    email_clean = req.email.strip().lower()
    conn = get_db()
    try:
        row = conn.execute(
            "SELECT hospital_id, hospital_name, email, password_hash FROM hospitals WHERE email = ?",
            (email_clean,),
        ).fetchone()
        if not row or not verify_password(req.password, row["password_hash"]):
            raise HTTPException(status_code=401, detail="Invalid email or password.")

        token = create_access_token(row["hospital_id"], row["hospital_name"], row["email"])
        return AuthResponse(
            access_token=token,
            hospital_id=row["hospital_id"],
            hospital_name=row["hospital_name"],
            email=row["email"],
        )
    finally:
        conn.close()


# ==============================================================================
# PATIENT CRUD & KOTLIN SYNC ENDPOINTS (SCOPED PER HOSPITAL)
# ==============================================================================
@app.get("/patients", response_model=List[PatientResponse])
def list_hospital_patients(
    ward: str = Query(default="ALL"),
    updated_since: int = Query(default=0, description="Unix epoch for incremental Kotlin sync"),
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> List[PatientResponse]:
    conn = get_db()
    try:
        if ward and ward.upper() != "ALL":
            rows = conn.execute(
                """
                SELECT * FROM patients
                WHERE hospital_id = ? AND LOWER(ward) = LOWER(?) AND updated_at_epoch >= ?
                """,
                (hospital["hospital_id"], ward.strip(), updated_since),
            ).fetchall()
        else:
            rows = conn.execute(
                """
                SELECT * FROM patients
                WHERE hospital_id = ? AND updated_at_epoch >= ?
                """,
                (hospital["hospital_id"], updated_since),
            ).fetchall()

        patients = [row_to_patient_response(r) for r in rows]
        patients.sort(
            key=lambda p: (
                TIER_PRIORITY.get(p.risk_tier, 3),
                p.estimated_hours_to_onset,
                -p.risk_score,
            )
        )
        return patients
    finally:
        conn.close()


@app.post("/patients", response_model=PatientResponse)
def add_or_update_patient(
    req: VitalsRequest,
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> PatientResponse:
    """Adds a new patient or updates an existing patient's vitals for the logged-in hospital."""
    result = run_inference(req)
    conn = get_db()
    try:
        upsert_patient_db(conn, hospital["hospital_id"], result)
        conn.commit()
        return result
    finally:
        conn.close()


@app.post("/patients/batch-sync", response_model=List[PatientResponse])
def batch_sync_patients(
    payload: BatchSyncRequest,
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> List[PatientResponse]:
    """Syncs multiple offline-recorded Kotlin patient entries in one request and returns the full updated queue."""
    conn = get_db()
    try:
        for req in payload.patients:
            pred = run_inference(req)
            upsert_patient_db(conn, hospital["hospital_id"], pred)
        conn.commit()

        rows = conn.execute(
            "SELECT * FROM patients WHERE hospital_id = ?", (hospital["hospital_id"],)
        ).fetchall()
        patients = [row_to_patient_response(r) for r in rows]
        patients.sort(
            key=lambda p: (
                TIER_PRIORITY.get(p.risk_tier, 3),
                p.estimated_hours_to_onset,
                -p.risk_score,
            )
        )
        return patients
    finally:
        conn.close()


@app.delete("/patients/{patient_id}")
def delete_single_patient(
    patient_id: str,
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> Dict[str, Any]:
    conn = get_db()
    try:
        cur = conn.execute(
            "DELETE FROM patients WHERE hospital_id = ? AND patient_id = ?",
            (hospital["hospital_id"], patient_id),
        )
        conn.commit()
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail=f"Patient '{patient_id}' not found in this hospital.")
        return {"deleted": True, "patient_id": patient_id}
    finally:
        conn.close()


@app.delete("/patients")
def delete_multiple_patients(
    payload: BulkDeleteRequest,
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> Dict[str, Any]:
    if not payload.patient_ids:
        return {"deleted_count": 0}
    conn = get_db()
    try:
        placeholders = ",".join("?" for _ in payload.patient_ids)
        params = [hospital["hospital_id"], *payload.patient_ids]
        cur = conn.execute(
            f"DELETE FROM patients WHERE hospital_id = ? AND patient_id IN ({placeholders})",
            params,
        )
        conn.commit()
        return {"deleted_count": cur.rowcount, "patient_ids": payload.patient_ids}
    finally:
        conn.close()


@app.post("/patients/reset-demo", response_model=List[PatientResponse])
def reset_hospital_demo_patients(
    hospital: Dict[str, str] = Depends(get_current_hospital),
) -> List[PatientResponse]:
    conn = get_db()
    try:
        seed_hospital_demo_patients(conn, hospital["hospital_id"])
        conn.commit()
        rows = conn.execute(
            "SELECT * FROM patients WHERE hospital_id = ?", (hospital["hospital_id"],)
        ).fetchall()
        patients = [row_to_patient_response(r) for r in rows]
        patients.sort(
            key=lambda p: (
                TIER_PRIORITY.get(p.risk_tier, 3),
                p.estimated_hours_to_onset,
                -p.risk_score,
            )
        )
        return patients
    finally:
        conn.close()