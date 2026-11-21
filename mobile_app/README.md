# WardPulse Android App (`mobile_app`)

Kotlin + Jetpack Compose Android client for general-ward nurses to triage early sepsis and clinical deterioration risk from bedside vitals[cite: 6, 8, 9].

## Screens & Features
* **Auth (`AuthScreen.kt`)**
  * Hospital login and registration via Retrofit, plus offline safe entry[cite: 8, 9].
* **Ward Queue (`WardQueueScreen.kt`)**
  * Priority list of patients sorted by urgency (`RED`, `YELLOW`, `UNCERTAIN`, `GREEN`) and estimated hours to onset[cite: 8, 9].
  * Ward drawer filter, `CONNECTED` / `OFFLINE SAFE` indicator, and long-press edit/delete[cite: 8, 9].
* **Vitals Form (`VitalsFormScreen.kt`)**
  * Form to enter or edit patient details and 6 bedside vitals (`hr`, `sbp`, `dbp`, `temp`, `resp`, `o2sat`)[cite: 8, 9].
* **Alert Result (`AlertResultScreen.kt`)**
  * Shows risk tier/score, estimated onset window, Shock Index, MAP, clinical triggers, recommended action, and a button to mark a patient diagnosed[cite: 8, 9].

## Networking & Offline Sync
* **Endpoint:** Hardcoded in `SessionManager.BASE_URL` to `https://iqx24.onrender.com` with 65-second OkHttp timeouts and a startup health check in `MainActivity.kt`[cite: 8, 9].
* **Sync & Fallback:** Caches patients and unsynced vitals locally in `SessionManager.kt`, replays queued vitals via `POST /patients/batch-sync`, polls deltas with `updated_since`, and falls back to local Shock Index / MAP scoring (`computeLocalFallback`) when offline[cite: 8, 9].
* **Reminders:** Schedules periodic recheck notifications via `WorkManager` in `PatientReminderScheduler.kt` and cancels them on delete or diagnosis[cite: 8, 9].

## Project Structure
* `app/build.gradle.kts` & `app/src/main/AndroidManifest.xml` — Compose, Material Icons, Retrofit, OkHttp, Gson, WorkManager, and `INTERNET` / `POST_NOTIFICATIONS` / `usesCleartextTraffic`[cite: 6, 8, 10]
* `app/src/main/java/com/wardpulse/app/MainActivity.kt` — Screen state navigation, health wake-up, and queue refresh loop[cite: 8, 9]
* `app/src/main/java/com/wardpulse/app/model/WardModels.kt` — Request/response DTOs[cite: 8, 9]
* `app/src/main/java/com/wardpulse/app/network/` — `SessionManager.kt`, `WardApiService.kt`, `WardRepository.kt`[cite: 8, 9]
* `app/src/main/java/com/wardpulse/app/notifications/PatientReminderScheduler.kt` — Recheck reminder worker and notifications[cite: 8, 9]
* `app/src/main/java/com/wardpulse/app/ui/` — `DesignComponents.kt`, `AuthScreen.kt`, `WardQueueScreen.kt`, `VitalsFormScreen.kt`, `AlertResultScreen.kt`[cite: 8, 9]

## Run in Android Studio
1. Open `mobile_app` in Android Studio and click **Sync Now**[cite: 6, 8].
2. Select an emulator or device (API 26+) and click **Run**[cite: 8, 9].
