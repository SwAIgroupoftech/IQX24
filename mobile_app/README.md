# WardPulse — Android Triage App

Pocket Android app built with **Kotlin** and **Jetpack Compose** for general-ward nurses to triage early sepsis and clinical deterioration risk from routine bedside vitals.

## Key Features
- **Hospital Auth & Session Management:** Staff login/registration and offline safe entry via `SessionManager`.
- **Multi-Ward Queue:** Filter patients across wards (`General Ward A`–`F` or `All Wards`), sorted by risk tier (`RED`, `YELLOW`, `UNCERTAIN`, `GREEN`) and estimated hours to onset.
- **15-Second Vitals Entry:** Enter patient details and 6 vitals (`HR`, `SBP`, `DBP`, `Temp`, `Resp`, `O2Sat`) to compute Shock Index and MAP.
- **Offline Fallback & Sync:** Computes local fallback risk scores and queues unsynced vitals when network access drops, syncing once reconnected.
- **Recheck Reminders:** Schedules background reminders via `WorkManager` (`PatientReminderScheduler`).

## Backend Endpoint
Hardcoded to `https://iqx24.onrender.com` in `SessionManager.BASE_URL`.

## Build & Run
1. Open `mobile_app` in **Android Studio**.
2. Sync Gradle (`work-runtime-ktx`, Retrofit, OkHttp, Gson, Compose Material 3).
3. Run on an Android device or emulator (API 26+).