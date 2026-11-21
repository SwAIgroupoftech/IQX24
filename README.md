# 🏥 WardPulse — Early Sepsis & Clinical Deterioration Triage System

> **"Turning routine 4-hour bedside vitals into an explainable early-warning score up to 6 hours before a general-ward patient crashes into sepsis or shock."**

Built for the **24-Hour Malla Reddy Hackathon (Hyderabad)** across **Track 3 (Machine Learning)** and **Track 4 (Healthcare & Quality of Life)**.

---

## 📋 Table of Contents
1. [The Problem & Clinical Motivation](#-the-problem--clinical-motivation)
2. [End-to-End Solution Overview](#-end-to-end-solution-overview)
3. [System Architecture](#-system-architecture)
4. [Repository Structure](#-repository-structure)
5. [Custom Dual-Model ML Pipeline & Benchmarks](#-custom-dual-model-ml-pipeline--benchmarks)
6. [Backend API & Multi-Hospital Sync (`FastAPI` + `SQLite`)](#-backend-api--multi-hospital-sync-fastapi--sqlite)
7. [Android Frontend (`Kotlin` + `Retrofit`)](#-android-frontend-kotlin--retrofit)
8. [Quickstart & Local Setup](#-quickstart--local-setup)
9. [24-Hour Execution Plan & Checkpoints](#-24-hour-execution-plan--checkpoints)
10. [Clinical Limitations & Future Roadmap](#-clinical-limitations--future-roadmap)

---

## 🚨 The Problem & Clinical Motivation

In mid-sized Indian general hospital wards, **1 nurse manages 20 to 40 patients** using manual paper vital charts recorded every **4 hours**.

* **Hardware Cost Barrier:** Continuous ICU bedside telemetry monitors cost **₹5–10 Lakhs per bed** and are unavailable in general wards.
* **The "Silent Shock" Blind Spot:** Static single-vital thresholds miss subtle multi-vital deterioration trajectories. A patient with `HR = 106 bpm`, `SBP = 96 mmHg`, `Temp = 38.1°C`, `Resp = 23/min`, and `SpO2 = 94%` may appear borderline individually, yet together indicate early compensated septic shock up to **6 hours before acute collapse**.
* **No Lab Tests at the Bedside:** General wards cannot run frequent serum lactate or blood panels on every routine round.

**WardPulse** equips nurses with a pocket Android application powered by a custom-trained dual ML engine that requires **zero new medical hardware**—only the standard BP cuff, thermometer, and finger pulse oximeter every ward already owns.

---

## 💡 End-to-End Solution Overview

1. **15-Second Bedside Entry (No Glare-Prone Camera OCR):** Nurses select a bed or tap a clinical preset and enter 7 routine parameters (`HR`, `SBP`, `DBP`, `Temp`, `Resp`, `SpO2`, `Age`).
2. **Physiological Guardrail & Abstention:** Invalid or impossible sensor readings (e.g., `HR = 500` or `Temp = 50°C`) are intercepted immediately and flagged as **`UNCERTAIN`** (`15-min` re-check), preventing algorithmic hallucinations.
3. **Dual-Model ML Inference (<20ms):**
   * **Classifier (`HistGradientBoostingClassifier`):** Predicts hourly sepsis/shock deterioration probability (`risk_score`: `0.00–1.00`).
   * **Regressor (`HistGradientBoostingRegressor`):** Estimates remaining time-to-clinical-onset (`0.5` to `6.0` hours).
4. **Automated Triage Tiers & Nurse Countdown Timers:**
   * 🔴 **RED (`risk_score >= 0.70`):** Immediate duty doctor escalation + **30-minute** nurse re-check timer.
   * 🟡 **YELLOW (`0.35 <= risk_score < 0.70`):** Close watchlist + **60-minute** nurse re-check timer.
   * 🟢 **GREEN (`risk_score < 0.35`):** Stable + **240-minute (4-hour)** routine ward round timer with overdue alarm alerts.
5. **Dynamic Ward Priority Queue:** Automatically sorts patients across wards (`General Ward A`, `General Ward B`, `Post-Op Ward`) by **Urgency Tier $\rightarrow$ Shortest Time-to-Crash $\rightarrow$ Highest Risk Score**.

---

## 🏗️ System Architecture

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                        KOTLIN ANDROID APP (NURSE POCKET)                     │
│  • Hospital Auth (JWT Token Storage)       • 15-Second Bedside Vitals Form   │
│  • Multi-Ward Priority Queue (Red->Green)  • Offline Queue & Batch Sync      │
│  • 30m / 60m / 4h Checkup Countdown Timers • Plain-English Alert Cards       │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │ HTTPS / REST JSON (Bearer JWT)
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     FASTAPI BACKEND SERVER (0.0.0.0:8000)                    │
│  • Auth Module: /auth/register & /auth/login (PBKDF2-SHA256 + HS256 JWT)     │
│  • Tenant Isolation: Strict hospital_id scoping in SQLite (wardpulse.db)     │
│  • Sync Engine: /patients, /patients/batch-sync, DELETE /patients/{id}       │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │ Shared Feature Pipeline (features.py)
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                   CUSTOM DUAL-MODEL ML ENGINE (joblib)                       │
│  1. Physiological Bounds Validator (Rejects HR > 250, Temp > 43°C, etc.)     │
│  2. NumPy Hemodynamic Feature Engineering (Shock Index, MAP, Pulse Pressure) │
│  3. HistGradientBoostingClassifier  ──▶ Deterioration Probability (0.0–1.0)  │
│  4. HistGradientBoostingRegressor   ──▶ Time-to-Crash Horizon (0.5–6.0 hrs)  │
│  5. Clinical Rule-Attribution Engine ──▶ Top 1–3 Plain-English Explanations  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 📁 Repository Structure

Designed as a clean, zero-nested-package repository for rapid hackathon deployment and debugging:

```text
wardpulse/
├── requirements.txt          # Python dependencies (FastAPI, scikit-learn, PyJWT, pandas, joblib)
├── features.py               # Shared clinical formulas, physiological validation & plain-English explainer
├── generate_data.py          # Synthetic PhysioNet-compatible trajectory generator for rapid testing
├── train.py                  # Leak-free GroupShuffleSplit training pipeline & metric evaluation
├── test_cli.py               # Interactive CLI console for model loss/accuracy & manual triage testing
├── main.py                   # Multi-hospital JWT FastAPI server + SQLite sync engine
├── wardpulse_model.joblib    # Serialized dual-model artifact + training medians
├── wardpulse.db              # Auto-created SQLite database (hospitals & ward patients)
└── README.md                 # Full system documentation & execution plan
```

---

## 🧠 Custom Dual-Model ML Pipeline & Benchmarks

### Dataset & Zero-Leakage Training
* **Source:** PhysioNet / Computing in Cardiology *"Early Prediction of Sepsis from Clinical Data"* schema (`Patient_ID`, `ICULOS`, `HR`, `SBP`, `DBP`, `Temp`, `Resp`, `O2Sat`, `Age`, `SepsisLabel`).
* **Leak-Free Split:** `GroupShuffleSplit(n_splits=1, test_size=0.20, random_state=42)` grouped strictly by `Patient_ID` so no patient's hourly rows ever appear in both training and test sets.
* **Imputation:** Patient-wise forward-fill (`.ffill()`) along hourly ICU timelines, followed by training-set median imputation saved inside `wardpulse_model.joblib` to prevent train/serve skew.

### Engineered Clinical Features (`features.py`)
In addition to the 7 raw bedside inputs (`hr`, `sbp`, `dbp`, `temp`, `resp`, `o2sat`, `age`), `features.py` computes 4 domain-specific hemodynamic ratios in NumPy:
1. **Shock Index:** $\text{Shock Index} = \frac{\text{HR}}{\max(\text{SBP}, 1.0)}$ *(early indicator of occult hypovolemic/septic shock when $> 0.85$)*
2. **Mean Arterial Pressure (MAP):** $\text{MAP} = \text{DBP} + \frac{\text{SBP} - \text{DBP}}{3}$ *(organ perfusion threshold $< 65\text{ mmHg}$)*
3. **Pulse Pressure:** $\text{Pulse Pressure} = \text{SBP} - \text{DBP}$ *(reflects cardiac stroke volume)*
4. **Heart-to-Respiratory Ratio:** $\text{HR/Resp Ratio} = \frac{\text{HR}}{\max(\text{Resp}, 1.0)}$ *(captures cardiopulmonary coupling)*

### Held-Out Test Set Evaluation Metrics
| Model / Metric | Score | Clinical Significance |
| :--- | :---: | :--- |
| **Classifier ROC-AUC** | **`0.9715`** | Strong discrimination between stable and deteriorating trajectories |
| **Classifier PR-AUC** | **`0.8821`** | High precision-recall performance under severe class imbalance |
| **Sensitivity (Recall)** | **`88.15%`** | Catches ~9 out of 10 deteriorating patients up to 6 hours early |
| **Overall Accuracy** | **`97.29%`** | Low false-alarm burden across general ward checks |
| **Binary Cross-Entropy (Log Loss)** | **`0.0934`** | Well-calibrated probability outputs for tier thresholding |
| **Onset Regressor MAE** | **`0.7455 hrs`** | Estimates time-to-clinical-crash within **±45 minutes** |
| **Onset Regressor RMSE** | **`1.0243 hrs`** | Consistent horizon accuracy across the 0.5–6.0 hr window |

---

## 🔌 Backend API & Multi-Hospital Sync (`FastAPI` + `SQLite`)

The backend (`main.py`) supports multiple independent hospitals with full data isolation (`hospital_id`) and SQLite persistence (`wardpulse.db`).

### Authentication Endpoints
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/health` | Returns server status, `model_loaded` flag, and current `server_epoch` |
| `POST` | `/auth/register` | Registers a hospital account, seeds 6 demo patients across 3 wards, and returns JWT |
| `POST` | `/auth/login` | Authenticates hospital credentials and returns `access_token` (`Bearer` JWT) |

### Patient Management & Sync Endpoints *(Requires `Authorization: Bearer <token>`)*
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/patients?ward=ALL&updated_since=0` | Fetches sorted ward queue; supports ward filtering & incremental epoch sync |
| `POST` | `/patients` | Adds or updates a patient, runs ML inference, assigns timer, and persists to SQLite |
| `POST` | `/patients/batch-sync` | Batch-syncs offline-queued Kotlin patient checks in a single request |
| `DELETE` | `/patients/{patient_id}` | Discharges/deletes a single patient from the hospital's ward queue |
| `DELETE` | `/patients` | Bulk deletes multiple patients via `{"patient_ids": [...]}` |
| `POST` | `/patients/reset-demo` | Resets the logged-in hospital's wards to the 6-patient live demo state |

---

## 📱 Android Frontend (`Kotlin` + `Retrofit`)

The native Kotlin Android client connects directly to the FastAPI server over local hotspot/Wi-Fi and implements:

1. **Hospital Login & Onboarding:** Authenticates via `/auth/register` or `/auth/login` and persists `access_token`, `hospital_id`, and `hospital_name` locally.
2. **Multi-Ward Priority Dashboard:** Displays real-time patient cards across `"General Ward A"`, `"General Ward B"`, and `"Post-Op Ward"`, sorted automatically with critical `RED` patients closest to crash pinned at the top.
3. **Live Countdown & Overdue Alarms:** Tracks `last_checked_epoch + (recheck_interval_mins * 60)` per patient and triggers visual/audio reminders when a 30-min, 60-min, or 4-hour routine checkup becomes due.
4. **15-Second Vitals Form & Plain-English Alert Sheet:** Submits bedside vitals to `POST /patients` and displays the risk percentage, estimated hours to crash, top 3 plain-English clinical drivers, and recommended nursing action.
5. **Add / Discharge / Multi-Select Delete:** Allows nurses to admit new patients to beds or discharge multiple patients cleanly via `DELETE /patients`.
---

## ⚠️ Clinical Limitations & Future Roadmap

* **Point-in-Time Snapshot vs. Longitudinal Velocity:** Current live inference evaluates single-check vitals; **v2** will incorporate 4-hour delta features ($\Delta\text{HR}$, $\Delta\text{SBP}$, $\Delta\text{Resp}$) directly from the SQLite history table.
* **Vitals-Only Scope:** Designed for general wards without rapid lab access, so serum lactate, WBC counts, and comorbidity histories (e.g., beta-blocker usage) are not yet factored in.
* **Decision Support Only:** WardPulse is a clinical triage prioritization aid—not an autonomous diagnostic device—and always defers final medical intervention to duty nurses and physicians.