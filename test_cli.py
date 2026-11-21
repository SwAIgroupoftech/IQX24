import os
import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    confusion_matrix,
    f1_score,
    log_loss,
    mean_absolute_error,
    mean_squared_error,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import GroupShuffleSplit, train_test_split

from features import (
    DEFAULT_MEDIANS,
    FEATURE_COLS,
    RAW_FEATURES,
    compute_features_df,
    compute_single_features,
    generate_reasons,
    validate_vitals,
)
from train import MODEL_ARTIFACT, compute_hours_to_onset, find_csv, normalize_columns


def load_bundle():
    if not os.path.exists(MODEL_ARTIFACT):
        raise FileNotFoundError(
            f"'{MODEL_ARTIFACT}' not found. Run 'python train.py' first!"
        )
    return joblib.load(MODEL_ARTIFACT)


def evaluate_model_metrics(bundle: dict) -> None:
    """Evaluates Log-Loss, Accuracy, ROC-AUC, PR-AUC, F1, and Onset MAE on the held-out test set."""
    csv_path = find_csv()
    print(f"\nLoading test split from '{csv_path}' to compute Loss & Accuracy...")
    df = normalize_columns(pd.read_csv(csv_path))

    if "patient_id" in df.columns and "iculos" in df.columns:
        df = df.sort_values(["patient_id", "iculos"]).reset_index(drop=True)

    df["hours_to_onset"] = compute_hours_to_onset(df)
    df["sepsislabel"] = df["sepsislabel"].fillna(0).astype(int)

    if "patient_id" in df.columns:
        splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
        _, test_idx = next(splitter.split(df, df["sepsislabel"], df["patient_id"]))
        test_df = df.iloc[test_idx].copy()
        test_df[RAW_FEATURES] = test_df.groupby("patient_id")[RAW_FEATURES].ffill()
    else:
        _, test_df = train_test_split(
            df, test_size=0.2, stratify=df["sepsislabel"], random_state=42
        )

    medians = bundle.get("medians", DEFAULT_MEDIANS)
    feature_cols = bundle.get("feature_cols", FEATURE_COLS)
    test_df[RAW_FEATURES] = test_df[RAW_FEATURES].fillna(medians)

    X_test = compute_features_df(test_df)[feature_cols]
    y_test_cls = test_df["sepsislabel"].to_numpy(dtype=int)
    y_test_reg = test_df["hours_to_onset"].to_numpy(dtype=np.float64)

    clf = bundle["classifier"]
    reg = bundle["regressor"]

    probs = clf.predict_proba(X_test)[:, 1]
    preds = (probs >= 0.50).astype(int)

    cls_loss = log_loss(y_test_cls, probs)
    acc = accuracy_score(y_test_cls, preds)
    roc_auc = roc_auc_score(y_test_cls, probs)
    pr_auc = average_precision_score(y_test_cls, probs)
    prec = precision_score(y_test_cls, preds, zero_division=0)
    rec = recall_score(y_test_cls, preds, zero_division=0)
    f1 = f1_score(y_test_cls, preds, zero_division=0)
    cm = confusion_matrix(y_test_cls, preds)

    pos_mask = y_test_cls == 1
    if pos_mask.sum() > 0:
        reg_preds = np.clip(reg.predict(X_test.iloc[pos_mask]), 0.5, 6.0)
        reg_mae = mean_absolute_error(y_test_reg[pos_mask], reg_preds)
        reg_rmse = float(np.sqrt(mean_squared_error(y_test_reg[pos_mask], reg_preds)))
    else:
        reg_preds = np.clip(reg.predict(X_test), 0.5, 6.0)
        reg_mae = mean_absolute_error(y_test_reg, reg_preds)
        reg_rmse = float(np.sqrt(mean_squared_error(y_test_reg, reg_preds)))

    print("\n" + "=" * 60)
    print("  WARDPULSE MODEL LOSS & ACCURACY REPORT (HELD-OUT TEST SET)")
    print("=" * 60)
    print(f"  Binary Cross-Entropy (Log Loss) : {cls_loss:.4f}")
    print(f"  Overall Accuracy                : {acc * 100:.2f}%")
    print(f"  ROC-AUC                         : {roc_auc:.4f}")
    print(f"  PR-AUC                          : {pr_auc:.4f}")
    print(f"  Precision                       : {prec:.4f}")
    print(f"  Recall (Sensitivity)            : {rec:.4f}")
    print(f"  F1-Score                        : {f1:.4f}")
    print("-" * 60)
    print(f"  Onset-Time Regressor MAE (Loss) : {reg_mae:.4f} hours")
    print(f"  Onset-Time Regressor RMSE       : {reg_rmse:.4f} hours")
    print("-" * 60)
    print(f"  Confusion Matrix (Threshold = 0.50):")
    print(f"    True Negatives (Safe)   : {cm[0, 0]:,}")
    print(f"    False Positives (Alarm) : {cm[0, 1]:,}")
    print(f"    False Negatives (Missed): {cm[1, 0]:,}")
    print(f"    True Positives (Caught) : {cm[1, 1]:,}")
    print("=" * 60 + "\n")


def run_single_prediction(bundle: dict, vitals: dict, label: str = "Custom Patient") -> None:
    print("\n" + "-" * 60)
    print(f"  TRIAGE RESULT — {label}")
    print("-" * 60)
    print(
        f"  Inputs -> HR: {vitals['hr']} bpm | BP: {vitals['sbp']}/{vitals['dbp']} mmHg | "
        f"Temp: {vitals['temp']}°C | Resp: {vitals['resp']}/min | SpO2: {vitals['o2sat']}% | Age: {vitals['age']}"
    )

    if not validate_vitals(
        vitals["hr"], vitals["sbp"], vitals["dbp"], vitals["temp"], vitals["resp"], vitals["o2sat"]
    ):
        print("  Risk Tier        : UNCERTAIN (Invalid / Out-of-Bounds Sensor Reading)")
        print("  Re-Check Timer   : 15 mins")
        print("  Action           : Invalid reading detected. Re-check cuff/oximeter and re-enter vitals.")
        print("-" * 60 + "\n")
        return

    medians = bundle.get("medians", DEFAULT_MEDIANS)
    feature_cols = bundle.get("feature_cols", FEATURE_COLS)
    X_row = compute_single_features(vitals, medians=medians)[feature_cols]

    risk_score = float(np.clip(bundle["classifier"].predict_proba(X_row)[0, 1], 0.0, 1.0))
    est_hours = round(float(np.clip(bundle["regressor"].predict(X_row)[0], 0.5, 6.0)), 1)

    shock_index = round(float(vitals["hr"]) / max(float(vitals["sbp"]), 1.0), 2)
    map_val = round(float(vitals["dbp"]) + (float(vitals["sbp"]) - float(vitals["dbp"])) / 3.0, 1)

    if risk_score >= 0.70:
        tier = "RED (HIGH RISK)"
        timer = "30 mins"
        action = "URGENT: Call duty doctor now. Re-check vitals in 30 mins."
    elif risk_score >= 0.35:
        tier = "YELLOW (WATCHLIST)"
        timer = "60 mins"
        action = "WATCHLIST: Monitor closely and repeat vitals check in 60 mins."
    else:
        tier = "GREEN (STABLE)"
        timer = "240 mins (4 hours)"
        est_hours = 6.0
        action = "STABLE: Continue routine 4-hour ward checkup schedule."

    reasons = generate_reasons(vitals, shock_index, map_val, est_hours)

    print(f"  Risk Tier        : {tier}")
    print(f"  Risk Probability : {risk_score:.2f} ({int(round(risk_score * 100))}%)")
    print(f"  Est. Time-to-Crash: ~{est_hours} hours")
    print(f"  Nurse Timer      : {timer}")
    print(f"  Clinical Ratios  : Shock Index = {shock_index} | MAP = {map_val} mmHg")
    print("  Top Reasons      :")
    for r in reasons:
        print(f"    • {r}")
    print(f"  Action           : {action}")
    print("-" * 60 + "\n")


def prompt_float(prompt_text: str, default_val: float) -> float:
    raw = input(f"  {prompt_text} [{default_val}]: ").strip()
    if not raw:
        return default_val
    return float(raw)


def interactive_vitals_prompt(bundle: dict) -> None:
    print("\nEnter patient bedside vitals (press Enter to keep default value):")
    try:
        vitals = {
            "hr": prompt_float("Heart Rate (bpm)", 106.0),
            "sbp": prompt_float("Systolic BP (mmHg)", 96.0),
            "dbp": prompt_float("Diastolic BP (mmHg)", 60.0),
            "temp": prompt_float("Temperature (°C)", 38.1),
            "resp": prompt_float("Respiratory Rate (/min)", 23.0),
            "o2sat": prompt_float("Oxygen SpO2 (%)", 94.0),
            "age": prompt_float("Patient Age (years)", 58.0),
        }
        run_single_prediction(bundle, vitals, label="Manual CLI Entry")
    except ValueError:
        print("\n[Error] Please enter valid numeric numbers.\n")


def run_presets(bundle: dict) -> None:
    presets = [
        (
            "Case 1: Silent Early Shock (Looks Borderline)",
            {"hr": 106, "sbp": 96, "dbp": 60, "temp": 38.1, "resp": 23, "o2sat": 94, "age": 58},
        ),
        (
            "Case 2: Acute Deterioration (Critical Red)",
            {"hr": 122, "sbp": 86, "dbp": 52, "temp": 38.8, "resp": 27, "o2sat": 90, "age": 66},
        ),
        (
            "Case 3: Watchlist Fever + Elevated Pulse (Yellow)",
            {"hr": 96, "sbp": 106, "dbp": 66, "temp": 38.0, "resp": 21, "o2sat": 95, "age": 52},
        ),
        (
            "Case 4: Stable Post-Op Ward Patient (Green)",
            {"hr": 76, "sbp": 120, "dbp": 78, "temp": 36.8, "resp": 16, "o2sat": 98, "age": 41},
        ),
        (
            "Case 5: Nurse Typo Guardrail Test (HR = 500)",
            {"hr": 500, "sbp": 120, "dbp": 78, "temp": 36.8, "resp": 16, "o2sat": 98, "age": 41},
        ),
    ]
    for title, vitals in presets:
        run_single_prediction(bundle, vitals, label=title)


def main() -> None:
    bundle = load_bundle()
    print("\nLoaded 'wardpulse_model.joblib' successfully.")

    while True:
        print("==========================================")
        print("  WARDPULSE CLI TEST & EVALUATION CONSOLE")
        print("==========================================")
        print("  1. Show Model Loss, Accuracy & Metrics")
        print("  2. Ask Model with Custom Vitals (CLI)")
        print("  3. Run All 5 Clinical Demo Test Cases")
        print("  4. Exit")
        choice = input("Select an option (1-4): ").strip()

        if choice == "1":
            evaluate_model_metrics(bundle)
        elif choice == "2":
            interactive_vitals_prompt(bundle)
        elif choice == "3":
            run_presets(bundle)
        elif choice == "4":
            print("Exiting WardPulse CLI.")
            break
        else:
            print("Invalid choice. Please type 1, 2, 3, or 4.\n")


if __name__ == "__main__":
    main()