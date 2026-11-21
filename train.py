import os
from pathlib import Path
from typing import Dict, Optional
import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.metrics import (
    average_precision_score,
    f1_score,
    mean_absolute_error,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import GroupShuffleSplit, train_test_split

from features import DEFAULT_MEDIANS, FEATURE_COLS, RAW_FEATURES, compute_features_df

MODEL_ARTIFACT = "wardpulse_model.joblib"
CSV_CANDIDATES = [
    "Dataset.csv",
    "Sepsis.csv",
    "sepsis.csv",
    "dataset.csv",
    "data/raw/Dataset.csv",
]


def find_csv() -> str:
    for path in CSV_CANDIDATES:
        if os.path.exists(path):
            return path
    for file in Path(".").glob("*.csv"):
        return str(file)
    raise FileNotFoundError(
        "No dataset CSV found. Place 'Dataset.csv' or 'Sepsis.csv' in this folder."
    )


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    col_map: Dict[str, str] = {}
    lower_lookup = {c.lower().strip(): c for c in df.columns}

    aliases = {
        "hr": ["hr", "heart_rate", "heartrate"],
        "sbp": ["sbp", "systolic_bp", "sysbp"],
        "dbp": ["dbp", "diastolic_bp", "diasbp"],
        "temp": ["temp", "temperature"],
        "resp": ["resp", "respiratory_rate", "resprate"],
        "o2sat": ["o2sat", "spo2", "oxygen_saturation"],
        "age": ["age"],
        "sepsislabel": ["sepsislabel", "sepsis_label", "target", "label"],
        "patient_id": ["patient_id", "patientid", "id", "subject_id"],
        "iculos": ["iculos", "hour", "hours", "time"],
    }

    for canonical, options in aliases.items():
        for opt in options:
            if opt in lower_lookup:
                col_map[lower_lookup[opt]] = canonical
                break

    return df.rename(columns=col_map)


def compute_hours_to_onset(df: pd.DataFrame) -> pd.Series:
    """
    Computes remaining hours until first SepsisLabel == 1 per patient trajectory,
    clamped strictly to [0.5, 6.0]. Non-sepsis rows default to 6.0.
    """
    hours = np.full(len(df), 6.0, dtype=np.float64)

    if "patient_id" in df.columns:
        for _, grp in df.groupby("patient_id", sort=False):
            pos_positions = np.where(grp["sepsislabel"].to_numpy() == 1)[0]
            if len(pos_positions) == 0:
                continue

            first_pos = pos_positions[0]
            idx_vals = grp.index.to_numpy()

            if "iculos" in grp.columns and grp["iculos"].notna().all():
                t = grp["iculos"].to_numpy(dtype=np.float64)
                # PhysioNet sets SepsisLabel=1 starting 6 hours before clinical onset (t_onset = t[first_pos] + 6)
                t_onset = t[first_pos] + 6.0
                rem = np.clip(t_onset - t, 0.5, 6.0)
                hours[idx_vals] = rem
            else:
                steps_from_first = np.arange(len(grp)) - first_pos
                # At first positive flag, ~6.0 hrs remain; decrements by 1 hr per step down to 0.5
                rem = np.clip(6.0 - np.maximum(steps_from_first, 0), 0.5, 6.0)
                hours[idx_vals] = rem
    else:
        pos_mask = df["sepsislabel"].to_numpy() == 1
        hours[pos_mask] = 2.5

    return pd.Series(hours, index=df.index)


def train() -> None:
    csv_path = find_csv()
    print(f"[1/5] Loading dataset from '{csv_path}'...")
    raw_df = pd.read_csv(csv_path)
    df = normalize_columns(raw_df)

    required = set(RAW_FEATURES + ["sepsislabel"])
    missing_req = required - set(df.columns)
    if missing_req:
        raise ValueError(f"Dataset missing required columns: {missing_req}")

    if "patient_id" in df.columns and "iculos" in df.columns:
        df = df.sort_values(["patient_id", "iculos"]).reset_index(drop=True)

    print(f"      Loaded {len(df):,} rows.")

    # Compute target onset-time horizon before splitting
    df["hours_to_onset"] = compute_hours_to_onset(df)
    df["sepsislabel"] = df["sepsislabel"].fillna(0).astype(int)

    # Zero data leakage split grouped by patient_id
    print("[2/5] Performing leak-free train/test split...")
    if "patient_id" in df.columns:
        splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
        train_idx, test_idx = next(splitter.split(df, df["sepsislabel"], df["patient_id"]))
        train_df = df.iloc[train_idx].copy()
        test_df = df.iloc[test_idx].copy()
        print(
            f"      Grouped by patient_id: {train_df['patient_id'].nunique():,} train patients | "
            f"{test_df['patient_id'].nunique():,} test patients"
        )
    else:
        train_df, test_df = train_test_split(
            df, test_size=0.2, stratify=df["sepsislabel"], random_state=42
        )
        print("      Stratified 80/20 split (no patient_id column present).")

    # Forward-fill within patient trajectories separately in train & test
    print("[3/5] Forward-filling vitals and computing training medians...")
    if "patient_id" in train_df.columns:
        train_df[RAW_FEATURES] = train_df.groupby("patient_id")[RAW_FEATURES].ffill()
        test_df[RAW_FEATURES] = test_df.groupby("patient_id")[RAW_FEATURES].ffill()

    feature_medians: Dict[str, float] = {}
    for col in RAW_FEATURES:
        med = float(train_df[col].median())
        feature_medians[col] = med if not np.isnan(med) else DEFAULT_MEDIANS[col]

    train_df[RAW_FEATURES] = train_df[RAW_FEATURES].fillna(feature_medians)
    test_df[RAW_FEATURES] = test_df[RAW_FEATURES].fillna(feature_medians)

    X_train = compute_features_df(train_df)
    y_train_cls = train_df["sepsislabel"].to_numpy(dtype=int)
    y_train_reg = train_df["hours_to_onset"].to_numpy(dtype=np.float64)

    X_test = compute_features_df(test_df)
    y_test_cls = test_df["sepsislabel"].to_numpy(dtype=int)
    y_test_reg = test_df["hours_to_onset"].to_numpy(dtype=np.float64)

    # Model 1: Deterioration Risk Classifier
    print("[4/5] Training HistGradientBoostingClassifier & HistGradientBoostingRegressor...")
    risk_classifier = HistGradientBoostingClassifier(
        class_weight="balanced",
        max_depth=6,
        learning_rate=0.05,
        random_state=42,
    )
    risk_classifier.fit(X_train, y_train_cls)

    # Model 2: Onset-Time Regressor trained on positive sepsis trajectories
    pos_train_mask = y_train_cls == 1
    if pos_train_mask.sum() >= 20:
        X_train_pos = X_train.iloc[pos_train_mask]
        y_train_pos_reg = y_train_reg[pos_train_mask]
    else:
        X_train_pos = X_train
        y_train_pos_reg = y_train_reg

    onset_regressor = HistGradientBoostingRegressor(
        max_depth=5,
        learning_rate=0.05,
        random_state=42,
    )
    onset_regressor.fit(X_train_pos, y_train_pos_reg)

    # Evaluation
    print("[5/5] Evaluating on held-out test set...")
    test_probs = risk_classifier.predict_proba(X_test)[:, 1]
    test_preds = (test_probs >= 0.50).astype(int)

    roc_auc = roc_auc_score(y_test_cls, test_probs)
    pr_auc = average_precision_score(y_test_cls, test_probs)
    recall = recall_score(y_test_cls, test_preds, zero_division=0)
    precision = precision_score(y_test_cls, test_preds, zero_division=0)
    f1 = f1_score(y_test_cls, test_preds, zero_division=0)

    pos_test_mask = y_test_cls == 1
    if pos_test_mask.sum() > 0:
        reg_preds = np.clip(onset_regressor.predict(X_test.iloc[pos_test_mask]), 0.5, 6.0)
        reg_mae = mean_absolute_error(y_test_reg[pos_test_mask], reg_preds)
    else:
        reg_preds = np.clip(onset_regressor.predict(X_test), 0.5, 6.0)
        reg_mae = mean_absolute_error(y_test_reg, reg_preds)

    print("\n" + "=" * 56)
    print("WARDPULSE DUAL-MODEL EVALUATION METRICS (COPY TO README)")
    print("=" * 56)
    print(f"Classifier ROC-AUC       : {roc_auc:.4f}")
    print(f"Classifier PR-AUC        : {pr_auc:.4f}")
    print(f"Classifier Recall        : {recall:.4f}")
    print(f"Classifier Precision     : {precision:.4f}")
    print(f"Classifier F1-Score      : {f1:.4f}")
    print(f"Onset Regressor MAE (hrs): {reg_mae:.4f}")
    print("=" * 56 + "\n")

    bundle = {
        "classifier": risk_classifier,
        "regressor": onset_regressor,
        "feature_cols": FEATURE_COLS,
        "medians": feature_medians,
    }
    joblib.dump(bundle, MODEL_ARTIFACT)
    print(f"Saved trained model artifact to '{MODEL_ARTIFACT}'.")


if __name__ == "__main__":
    train()