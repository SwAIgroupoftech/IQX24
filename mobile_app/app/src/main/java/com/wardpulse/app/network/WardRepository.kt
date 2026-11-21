package com.wardpulse.app.network

import com.wardpulse.app.model.*
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WardRepository(private val sessionManager: SessionManager) {

    private var cachedPatients: MutableList<PatientResponse> = sessionManager.loadCachedPatients()
    private val pendingOfflineSync: MutableList<VitalsRequest> = sessionManager.loadPendingSync()

    private fun buildApi(baseUrl: String = SessionManager.BASE_URL): WardPulseApiService {
        val cleanUrl = "${SessionManager.BASE_URL}/"
        val client = OkHttpClient.Builder()
            .connectTimeout(65, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(65, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(cleanUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WardPulseApiService::class.java)
    }

    private fun requireToken(): String = sessionManager.getBearerToken() ?: "Bearer offline-demo-token"

    private fun persistLocalState() {
        sessionManager.saveCachedPatients(cachedPatients)
        sessionManager.savePendingSync(pendingOfflineSync)
    }

    suspend fun checkHealth(baseUrl: String = SessionManager.BASE_URL): Result<HealthResponse> {
        return try {
            Result.success(buildApi().healthCheck())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- AUTH ---
    suspend fun login(baseUrl: String = SessionManager.BASE_URL, email: String, password: String): Result<AuthResponse> {
        return try {
            val cleanEmail = email.trim()
            val res = buildApi().loginHospital(LoginRequest(cleanEmail, password))
            val resolvedHospital = res.hospitalName ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
            val resolvedId = res.hospitalId ?: "HOSP_${cleanEmail.hashCode()}"
            sessionManager.saveSession(res.accessToken, resolvedId, resolvedHospital, SessionManager.BASE_URL)
            sessionManager.saveLastSyncEpoch(0L)
            cachedPatients = sessionManager.loadCachedPatients()
            pendingOfflineSync.clear()
            pendingOfflineSync.addAll(sessionManager.loadPendingSync())
            Result.success(res)
        } catch (e: HttpException) {
            val msg = if (e.code() == 401 || e.code() == 404) {
                "Account not found or wrong password. Tap 'Sign up here' below to register first."
            } else {
                "Server error (${e.code()}): ${e.message()}"
            }
            Result.failure(Exception(msg))
        } catch (e: Exception) {
            Result.failure(Exception("Server unreachable (${SessionManager.BASE_URL}). Check internet connection or use Offline Safe Entry."))
        }
    }

    suspend fun register(
        baseUrl: String = SessionManager.BASE_URL,
        hospitalName: String,
        email: String,
        password: String,
        seedDemoPatients: Boolean = false
    ): Result<AuthResponse> {
        return try {
            val cleanEmail = email.trim()
            val cleanHospital = hospitalName.trim().ifBlank { "General Hospital" }
            val res = buildApi().registerHospital(
                RegisterRequest(
                    hospitalName = cleanHospital,
                    email = cleanEmail,
                    password = password,
                    seedDemoPatients = seedDemoPatients
                )
            )
            val resolvedId = res.hospitalId ?: "HOSP_${cleanEmail.hashCode()}"
            sessionManager.saveSession(res.accessToken, resolvedId, res.hospitalName ?: cleanHospital, SessionManager.BASE_URL)
            sessionManager.saveLastSyncEpoch(0L)
            cachedPatients.clear()
            pendingOfflineSync.clear()
            persistLocalState()
            Result.success(res)
        } catch (e: HttpException) {
            val msg = if (e.code() == 400 || e.code() == 409) {
                "Email already registered. Tap 'Log in' below to access your hospital."
            } else {
                "Registration error (${e.code()}): ${e.message()}"
            }
            Result.failure(Exception(msg))
        } catch (e: Exception) {
            Result.failure(Exception("Server unreachable (${SessionManager.BASE_URL}). Check internet connection or use Offline Safe Entry."))
        }
    }

    fun enterOfflineMode(baseUrl: String = SessionManager.BASE_URL, hospitalName: String = "WardPulse Offline Hospital") {
        sessionManager.saveSession(
            token = "offline-local-token",
            hospitalId = "HOSP_OFFLINE",
            hospitalName = hospitalName.ifBlank { "WardPulse Offline Hospital" },
            serverUrl = SessionManager.BASE_URL
        )
        cachedPatients = sessionManager.loadCachedPatients()
        pendingOfflineSync.clear()
        pendingOfflineSync.addAll(sessionManager.loadPendingSync())
    }

    // --- PATIENTS & INCREMENTAL DELTA SYNC ---
    suspend fun fetchWardQueue(
        baseUrl: String = SessionManager.BASE_URL,
        wardFilter: String = "ALL",
        forceFullRefresh: Boolean = false
    ): Pair<List<PatientResponse>, Boolean> {
        return try {
            val api = buildApi()
            val token = requireToken()

            if (pendingOfflineSync.isNotEmpty()) {
                val synced = api.batchSyncPatients(token, BatchSyncRequest(pendingOfflineSync.toList()))
                synced.forEach { upsertLocalCache(it) }
                pendingOfflineSync.clear()
                persistLocalState()
            }

            val sinceEpoch = if (forceFullRefresh || cachedPatients.isEmpty()) 0L else sessionManager.getLastSyncEpoch()
            val response = api.getPatients(bearerToken = token, ward = "ALL", updatedSince = sinceEpoch)

            if (sinceEpoch == 0L) {
                cachedPatients = sortPatients(response).toMutableList()
            } else {
                response.forEach { upsertLocalCache(it) }
                cachedPatients = sortPatients(cachedPatients).toMutableList()
            }

            val maxServerEpoch = response.maxOfOrNull { it.updatedAtEpoch ?: it.lastCheckedEpoch }
            if (maxServerEpoch != null && maxServerEpoch > sinceEpoch) {
                sessionManager.saveLastSyncEpoch(maxServerEpoch)
            }
            persistLocalState()

            val filtered = if (wardFilter == "ALL") cachedPatients else cachedPatients.filter { it.ward.equals(wardFilter, ignoreCase = true) }
            Pair(sortPatients(filtered), false)
        } catch (e: Exception) {
            val filtered = if (wardFilter == "ALL") cachedPatients else cachedPatients.filter { it.ward.equals(wardFilter, ignoreCase = true) }
            Pair(sortPatients(filtered), true)
        }
    }

    suspend fun analyzeAndUpdate(baseUrl: String = SessionManager.BASE_URL, req: VitalsRequest): Pair<PatientResponse, Boolean> {
        val stampedReq = req.copy(clientCheckedEpoch = System.currentTimeMillis() / 1000L)
        return try {
            val result = buildApi().addOrUpdatePatient(requireToken(), stampedReq)
            upsertLocalCache(result)
            persistLocalState()
            Pair(result, false)
        } catch (e: Exception) {
            pendingOfflineSync.removeAll { it.patientId == stampedReq.patientId }
            pendingOfflineSync.add(stampedReq)
            val fallbackResult = computeLocalFallback(stampedReq)
            upsertLocalCache(fallbackResult)
            persistLocalState()
            Pair(fallbackResult, true)
        }
    }

    suspend fun deletePatient(baseUrl: String = SessionManager.BASE_URL, patientId: String): Pair<List<PatientResponse>, Boolean> {
        cachedPatients.removeAll { it.patientId == patientId }
        pendingOfflineSync.removeAll { it.patientId == patientId }
        persistLocalState()
        return try {
            buildApi().deletePatient(requireToken(), patientId)
            Pair(sortPatients(cachedPatients), false)
        } catch (e: Exception) {
            Pair(sortPatients(cachedPatients), true)
        }
    }

    suspend fun bulkDeletePatients(baseUrl: String = SessionManager.BASE_URL, patientIds: List<String>): Pair<List<PatientResponse>, Boolean> {
        val idSet = patientIds.toSet()
        cachedPatients.removeAll { it.patientId in idSet }
        pendingOfflineSync.removeAll { it.patientId in idSet }
        persistLocalState()
        return try {
            buildApi().bulkDeletePatients(requireToken(), BulkDeleteRequest(patientIds))
            Pair(sortPatients(cachedPatients), false)
        } catch (e: Exception) {
            Pair(sortPatients(cachedPatients), true)
        }
    }

    suspend fun resetDemoQueue(baseUrl: String = SessionManager.BASE_URL): Pair<List<PatientResponse>, Boolean> {
        return try {
            val resetList = buildApi().resetDemoPatients(requireToken())
            pendingOfflineSync.clear()
            sessionManager.saveLastSyncEpoch(0L)
            cachedPatients = sortPatients(resetList).toMutableList()
            persistLocalState()
            Pair(cachedPatients, false)
        } catch (e: Exception) {
            Pair(sortPatients(cachedPatients), true)
        }
    }

    fun getPendingSyncCount(): Int = pendingOfflineSync.size

    private fun sortPatients(list: List<PatientResponse>): List<PatientResponse> {
        val tierRank = mapOf("RED" to 0, "YELLOW" to 1, "UNCERTAIN" to 2, "GREEN" to 3)
        return list.sortedWith(
            compareBy<PatientResponse> { tierRank[it.riskTier.uppercase()] ?: 4 }
                .thenBy { it.estimatedHoursToOnset }
                .thenByDescending { it.riskScore }
        )
    }

    private fun upsertLocalCache(patient: PatientResponse) {
        val idx = cachedPatients.indexOfFirst { it.patientId == patient.patientId }
        if (idx >= 0) {
            cachedPatients[idx] = patient
        } else {
            cachedPatients.add(patient)
        }
    }

    private fun computeLocalFallback(req: VitalsRequest): PatientResponse {
        val nowEpoch = req.clientCheckedEpoch
        val shockIndex = ((req.hr / max(req.sbp, 1.0)) * 100).roundToInt() / 100.0
        val map = ((req.dbp + (req.sbp - req.dbp) / 3.0) * 10).roundToInt() / 10.0

        val isOutOfRange = req.hr < 25 || req.hr > 250 ||
                req.sbp < 40 || req.sbp > 260 ||
                req.dbp < 20 || req.dbp > 160 ||
                req.o2sat < 50 || req.o2sat > 100 ||
                req.temp < 32.0 || req.temp > 43.0 ||
                req.resp < 4 || req.resp > 60

        if (isOutOfRange) {
            return PatientResponse(
                patientId = req.patientId,
                name = req.name,
                ward = req.ward,
                bed = req.bed,
                riskScore = 0.0,
                riskTier = "UNCERTAIN",
                estimatedHoursToOnset = 6.0,
                recheckIntervalMins = 15,
                lastCheckedEpoch = nowEpoch,
                updatedAtEpoch = nowEpoch,
                shockIndex = shockIndex,
                map = map,
                topReasons = listOf("Vitals out of physiological limits (HR=${req.hr.toInt()})."),
                recommendedAction = "Re-check cuff/oximeter and re-enter valid vitals.",
                isOfflineFallback = true
            )
        }

        val reasons = mutableListOf<String>()
        var score = 0.12
        if (shockIndex >= 0.90) {
            score += 0.48
            reasons.add("Rapid Shock Index surge ($shockIndex): HR rising while SBP drops")
        } else if (shockIndex >= 0.78) {
            score += 0.24
            reasons.add("Borderline Shock Index ($shockIndex): early hemodynamic strain")
        }
        if (req.resp >= 22) {
            score += 0.22
            reasons.add("Tachypnea: breathing rate elevated (${req.resp.toInt()}/min)")
        }
        if (req.o2sat < 92) {
            score += 0.25
            reasons.add("Hypoxia warning: SpO2 dropped to ${req.o2sat.toInt()}%")
        }
        if (req.temp >= 38.3 || req.temp < 36.0) {
            score += 0.12
            reasons.add("Abnormal core temperature (${req.temp}°C)")
        }
        if (reasons.isEmpty()) reasons.add("All 6 bedside vitals and ratios within safe range")

        val clampedScore = (min(max(score, 0.05), 0.98) * 100).roundToInt() / 100.0
        val tier = when {
            clampedScore >= 0.70 -> "RED"
            clampedScore >= 0.35 -> "YELLOW"
            else -> "GREEN"
        }
        val hoursToOnset = when (tier) {
            "RED" -> (((1.0 - clampedScore) * 6.0 + 0.6).coerceIn(0.5, 2.8) * 10).roundToInt() / 10.0
            "YELLOW" -> (((1.0 - clampedScore) * 6.0 + 1.2).coerceIn(2.5, 5.0) * 10).roundToInt() / 10.0
            else -> 6.0
        }
        val intervalMins = when (tier) { "RED" -> 30; "YELLOW" -> 60; else -> 240 }
        val action = when (tier) {
            "RED" -> "URGENT: Call duty doctor now. Re-check vitals in 30 mins."
            "YELLOW" -> "WATCHLIST: Repeat vitals check in 60 mins."
            else -> "STABLE: Routine 4-hour ward checkup schedule."
        }

        return PatientResponse(
            patientId = req.patientId,
            name = req.name,
            ward = req.ward,
            bed = req.bed,
            riskScore = clampedScore,
            riskTier = tier,
            estimatedHoursToOnset = hoursToOnset,
            recheckIntervalMins = intervalMins,
            lastCheckedEpoch = nowEpoch,
            updatedAtEpoch = nowEpoch,
            shockIndex = shockIndex,
            map = map,
            topReasons = reasons,
            recommendedAction = action,
            isOfflineFallback = true
        )
    }

    companion object {
        val WARDS = listOf(
            "ALL",
            "General Ward A",
            "General Ward B",
            "General Ward C",
            "General Ward D",
            "Ward E",
            "Ward F"
        )
    }
}