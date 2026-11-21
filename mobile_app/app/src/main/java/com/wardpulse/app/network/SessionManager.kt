package com.wardpulse.app.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wardpulse.app.model.PatientResponse
import com.wardpulse.app.model.VitalsRequest

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("wardpulse_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val BASE_URL = "https://iqx24.onrender.com"
    }

    fun saveSession(token: String, hospitalId: String, hospitalName: String, serverUrl: String = BASE_URL) {
        prefs.edit()
            .putString("jwt_token", token)
            .putString("hospital_id", hospitalId)
            .putString("hospital_name", hospitalName)
            .putString("server_url", BASE_URL)
            .apply()
    }

    fun getBearerToken(): String? {
        val raw = prefs.getString("jwt_token", null) ?: return null
        return if (raw.startsWith("Bearer ", ignoreCase = true)) raw else "Bearer $raw"
    }

    fun getHospitalId(): String = prefs.getString("hospital_id", "HOSP_LOCAL") ?: "HOSP_LOCAL"

    fun getHospitalName(): String = prefs.getString("hospital_name", "General Hospital") ?: "General Hospital"

    fun getServerUrl(): String = BASE_URL

    fun saveServerUrl(url: String) {
        prefs.edit().putString("server_url", BASE_URL).apply()
    }

    fun getLastSyncEpoch(): Long = prefs.getLong("last_sync_epoch_${getHospitalId()}", 0L)

    fun saveLastSyncEpoch(epoch: Long) {
        prefs.edit().putLong("last_sync_epoch_${getHospitalId()}", epoch).apply()
    }

    fun saveCachedPatients(patients: List<PatientResponse>) {
        prefs.edit().putString("cached_patients_${getHospitalId()}", gson.toJson(patients)).apply()
    }

    fun loadCachedPatients(): MutableList<PatientResponse> {
        val json = prefs.getString("cached_patients_${getHospitalId()}", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<PatientResponse>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun savePendingSync(queue: List<VitalsRequest>) {
        prefs.edit().putString("pending_sync_${getHospitalId()}", gson.toJson(queue)).apply()
    }

    fun loadPendingSync(): MutableList<VitalsRequest> {
        val json = prefs.getString("pending_sync_${getHospitalId()}", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<VitalsRequest>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun getDiagnosedIds(): Set<String> =
        prefs.getStringSet("diagnosed_ids_${getHospitalId()}", emptySet()) ?: emptySet()

    fun markPatientDiagnosed(patientId: String) {
        val updated = getDiagnosedIds().toMutableSet().apply { add(patientId) }
        prefs.edit().putStringSet("diagnosed_ids_${getHospitalId()}", updated).apply()
    }

    fun isPatientDiagnosed(patientId: String): Boolean =
        getDiagnosedIds().contains(patientId)

    fun isDemoFastReminder(): Boolean =
        prefs.getBoolean("demo_fast_reminder", true)

    fun setDemoFastReminder(enabled: Boolean) {
        prefs.edit().putBoolean("demo_fast_reminder", enabled).apply()
    }

    fun isLoggedIn(): Boolean = !getBearerToken().isNullOrBlank()

    fun logout() {
        val hid = getHospitalId()
        prefs.edit()
            .remove("jwt_token")
            .remove("hospital_id")
            .remove("hospital_name")
            .remove("last_sync_epoch_$hid")
            .apply()
    }
}