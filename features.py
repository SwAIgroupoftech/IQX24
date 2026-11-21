from typing import Any, Dict, List, Union
import numpy as np
import pandas as pd

RAW_FEATURES: List[str] = ["hr", "sbp", "dbp", "temp", "resp", "o2sat", "age"]
ENGINEERED_FEATURES: List[str] = [
    "shock_index",
    "map",
    "pulse_pressure",
    "hr_resp_ratio",
]
FEATURE_COLS: List[str] = RAW_FEATURES + ENGINEERED_FEATURES

DEFAULT_MEDIANS: Dict[str, float] = {
    "hr": 84.0,
    "sbp": 118.0,
    "dbp": 66.0,
    "temp": 36.9,
    "resp": 18.0,
    "o2sat": 97.0,
    "age": 60.0,
}


def validate_vitals(
    hr: float,
    sbp: float,
    dbp: float,
    temp: float,
    resp: float,
    o2sat: float,
) -> bool:
    """Validates physiological bounds to reject sensor artifacts or nurse typos."""
    try:
        vals = [float(hr), float(sbp), float(dbp), float(temp), float(resp), float(o2sat)]
        if any(np.isnan(v) or np.isinf(v) for v in vals):
            return False
    except (TypeError, ValueError):
        return False

    if hr < 25 or hr > 250:
        return False
    if sbp < 40 or sbp > 260:
        return False
    if dbp < 20 or dbp > 160:
        return False
    if o2sat < 50 or o2sat > 100:
        return False
    if temp < 32.0 or temp > 43.0:
        return False
    if resp < 4 or resp > 60:
        return False
    if sbp <= dbp:
        return False

    return True


def compute_features_df(df: pd.DataFrame) -> pd.DataFrame:
    """Computes the 11 model input features (7 raw + 4 engineered) in NumPy."""
    out = df[RAW_FEATURES].copy()

    hr = out["hr"].to_numpy(dtype=np.float64)
    sbp = out["sbp"].to_numpy(dtype=np.float64)
    dbp = out["dbp"].to_numpy(dtype=np.float64)
    resp = out["resp"].to_numpy(dtype=np.float64)

    out["shock_index"] = hr / np.maximum(sbp, 1.0)
    out["map"] = dbp + (sbp - dbp) / 3.0
    out["pulse_pressure"] = sbp - dbp
    out["hr_resp_ratio"] = hr / np.maximum(resp, 1.0)

    return out[FEATURE_COLS]


def compute_single_features(
    vitals: Dict[str, Any],
    medians: Dict[str, float] = DEFAULT_MEDIANS,
) -> pd.DataFrame:
    """Imputes any missing raw vital with saved training medians and builds a 1-row feature frame."""
    row: Dict[str, float] = {}
    for col in RAW_FEATURES:
        val = vitals.get(col)
        if val is None or (isinstance(val, float) and np.isnan(val)):
            row[col] = float(medians.get(col, DEFAULT_MEDIANS[col]))
        else:
            row[col] = float(val)

    return compute_features_df(pd.DataFrame([row]))


def generate_reasons(
    vitals: Dict[str, Any],
    shock_index: float,
    map_val: float,
    hours_to_onset: float,
) -> List[str]:
    """Generates 1-3 crisp, nurse-friendly plain-English reasons explaining the triage score."""
    hr = float(vitals["hr"])
    sbp = float(vitals["sbp"])
    dbp = float(vitals["dbp"])
    temp = float(vitals["temp"])
    resp = float(vitals["resp"])
    o2sat = float(vitals["o2sat"])

    reasons: List[str] = []

    if shock_index >= 0.90:
        reasons.append(
            f"Rapid Shock Index surge ({shock_index:.2f}): estimated ~{hours_to_onset:.1f} hrs to clinical crash"
        )
    elif shock_index >= 0.78:
        reasons.append(
            f"Elevated Shock Index ({shock_index:.2f}): heart rate ({int(round(hr))} bpm) outpacing SBP ({int(round(sbp))} mmHg)"
        )

    if resp >= 22 and sbp <= 105:
        reasons.append(
            f"Tachypnea: breathing rate elevated ({int(round(resp))}/min) with dropping SBP ({int(round(sbp))} mmHg)"
        )
    elif resp >= 22:
        reasons.append(
            f"Tachypnea warning: breathing rate elevated at {int(round(resp))}/min"
        )

    if o2sat <= 94:
        reasons.append(
            f"Hypoxia warning: oxygen saturation dropped to {int(round(o2sat))}%"
        )

    if map_val < 65.0:
        reasons.append(
            f"Low Mean Arterial Pressure ({map_val:.1f} mmHg): early sign of reduced organ perfusion"
        )
    elif sbp < 95.0 and len(reasons) < 3:
        reasons.append(
            f"Hypotension alert: blood pressure low at {int(round(sbp))}/{int(round(dbp))} mmHg"
        )

    if temp >= 38.0:
        reasons.append(
            f"Fever spike ({temp:.1f}°C) paired with vital shifts suggests developing sepsis"
        )
    elif temp <= 35.8:
        reasons.append(
            f"Hypothermia ({temp:.1f}°C): low body temperature associated with systemic infection"
        )

    if not reasons:
        if hours_to_onset < 5.5:
            reasons.append(
                f"Subtle multi-vital drift detected across pulse and BP (~{hours_to_onset:.1f} hrs window)"
            )
        else:
            reasons.append(
                "All bedside vitals and hemodynamic ratios are within stable ward limits"
            )

    return reasons[:3]