import numpy as np
import pandas as pd


def generate_synthetic_sepsis_dataset(
    n_patients: int = 1200,
    sepsis_prevalence: float = 0.14,
    random_seed: int = 42,
    output_path: str = "Dataset.csv",
) -> None:
    rng = np.random.default_rng(random_seed)
    records = []

    print(f"Generating synthetic PhysioNet-style trajectories for {n_patients} patients...")

    for pid in range(1, n_patients + 1):
        patient_id = f"P{pid:05d}"
        stay_hours = int(rng.integers(14, 48))
        age = float(np.clip(rng.normal(61, 16), 18, 95))

        # Baseline patient-specific vitals
        base_hr = rng.normal(80, 10)
        base_sbp = rng.normal(122, 14)
        base_dbp = base_sbp * rng.uniform(0.58, 0.66)
        base_temp = rng.normal(36.9, 0.35)
        base_resp = rng.normal(16.5, 2.2)
        base_o2 = np.clip(rng.normal(97.5, 1.3), 93, 100)

        is_sepsis_patient = rng.random() < sepsis_prevalence
        # If sepsis patient, clinical crash occurs near the end of the stay;
        # PhysioNet sets SepsisLabel = 1 starting 6 hours before clinical onset.
        if is_sepsis_patient:
            onset_hour = int(rng.integers(max(8, stay_hours - 8), stay_hours))
            label_start_hour = max(1, onset_hour - 6)
        else:
            onset_hour = 9999
            label_start_hour = 9999

        for hour in range(1, stay_hours + 1):
            # Deterioration progression factor (0.0 when healthy -> 1.0+ approaching crash)
            if is_sepsis_patient and hour >= label_start_hour - 2:
                hours_until_crash = max(0.0, float(onset_hour - hour))
                severity = np.clip((8.0 - hours_until_crash) / 8.0, 0.0, 1.35)
            else:
                severity = 0.0

            hr = base_hr + (28.0 * severity) + rng.normal(0, 3.5)
            sbp = base_sbp - (26.0 * severity) + rng.normal(0, 4.5)
            dbp = base_dbp - (15.0 * severity) + rng.normal(0, 3.5)
            temp = base_temp + (1.35 * severity) + rng.normal(0, 0.2)
            resp = base_resp + (7.5 * severity) + rng.normal(0, 1.2)
            o2sat = base_o2 - (4.8 * severity) + rng.normal(0, 0.8)

            # Clamp to realistic clinical limits
            hr = round(float(np.clip(hr, 42, 185)), 1)
            sbp = round(float(np.clip(sbp, 62, 210)), 1)
            dbp = round(float(np.clip(dbp, 35, min(sbp - 10, 130))), 1)
            temp = round(float(np.clip(temp, 34.8, 41.2)), 2)
            resp = round(float(np.clip(resp, 8, 44)), 1)
            o2sat = round(float(np.clip(o2sat, 78, 100)), 1)

            sepsis_label = 1 if (is_sepsis_patient and hour >= label_start_hour) else 0

            # Simulate realistic intermittent ward charting (~15% random missingness after hour 1)
            if hour > 1:
                if rng.random() < 0.15:
                    temp = np.nan
                if rng.random() < 0.10:
                    resp = np.nan
                if rng.random() < 0.08:
                    sbp = np.nan
                    dbp = np.nan

            records.append(
                {
                    "Patient_ID": patient_id,
                    "ICULOS": hour,
                    "HR": hr,
                    "O2Sat": o2sat,
                    "Temp": temp,
                    "SBP": sbp,
                    "DBP": dbp,
                    "Resp": resp,
                    "Age": round(age, 0),
                    "SepsisLabel": sepsis_label,
                }
            )

    df = pd.DataFrame(records)
    df.to_csv(output_path, index=False)
    print(f"Saved '{output_path}' ({len(df):,} rows across {n_patients} patients).")
    print(f"Positive SepsisLabel rate: {df['SepsisLabel'].mean():.2%}")


if __name__ == "__main__":
    generate_synthetic_sepsis_dataset()