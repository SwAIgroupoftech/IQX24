package com.wardpulse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wardpulse.app.model.PatientResponse
import com.wardpulse.app.network.SessionManager
import com.wardpulse.app.network.WardRepository
import com.wardpulse.app.notifications.PatientReminderScheduler
import com.wardpulse.app.ui.AuthScreen
import com.wardpulse.app.ui.VitalsFormScreen
import com.wardpulse.app.ui.WardBgRose
import com.wardpulse.app.ui.WardDarkText
import com.wardpulse.app.ui.WardPrimaryRed
import com.wardpulse.app.ui.WardQueueScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ActiveScreen {
    AUTH, QUEUE, VITALS_FORM, RESULT
}

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val sessionManager = SessionManager(applicationContext)
        // Always start fresh on Login / Sign Up screen when opening the app
        sessionManager.logout()
        val repository = WardRepository(sessionManager)

        setContent {
            val scope = rememberCoroutineScope()
            val serverUrl = SessionManager.BASE_URL

            var currentScreen by remember { mutableStateOf(ActiveScreen.AUTH) }
            var hospitalName by remember { mutableStateOf(sessionManager.getHospitalName()) }
            var selectedWard by remember { mutableStateOf("ALL") }
            var patients by remember { mutableStateOf<List<PatientResponse>>(emptyList()) }
            var diagnosedIds by remember { mutableStateOf(sessionManager.getDiagnosedIds()) }
            var demoFastReminder by remember { mutableStateOf(sessionManager.isDemoFastReminder()) }
            var isOfflineMode by remember { mutableStateOf(false) }
            var isLoading by remember { mutableStateOf(false) }
            var isSubmitting by remember { mutableStateOf(false) }
            var selectedResult by remember { mutableStateOf<PatientResponse?>(null) }
            var editingPatient by remember { mutableStateOf<PatientResponse?>(null) }

            // Wake up https://iqx24.onrender.com immediately while user logs in
            LaunchedEffect(Unit) {
                repository.checkHealth(serverUrl)
            }

            fun refreshQueue(forceFull: Boolean = false) {
                scope.launch {
                    isLoading = true
                    val (list, offline) = repository.fetchWardQueue(
                        baseUrl = serverUrl,
                        wardFilter = selectedWard,
                        forceFullRefresh = forceFull
                    )
                    patients = list
                    isOfflineMode = offline
                    isLoading = false
                }
            }

            LaunchedEffect(currentScreen, selectedWard) {
                if (currentScreen == ActiveScreen.QUEUE) {
                    while (true) {
                        val (list, offline) = repository.fetchWardQueue(serverUrl, selectedWard, false)
                        patients = list
                        isOfflineMode = offline
                        delay(15_000L)
                    }
                }
            }

            MaterialTheme {
                when (currentScreen) {
                    ActiveScreen.AUTH -> {
                        AuthScreen(
                            repository = repository,
                            onAuthenticated = {
                                hospitalName = sessionManager.getHospitalName()
                                diagnosedIds = sessionManager.getDiagnosedIds()
                                currentScreen = ActiveScreen.QUEUE
                                refreshQueue(forceFull = true)
                            }
                        )
                    }

                    ActiveScreen.QUEUE -> {
                        WardQueueScreen(
                            hospitalName = hospitalName,
                            patients = patients,
                            diagnosedIds = diagnosedIds,
                            selectedWard = selectedWard,
                            isOfflineMode = isOfflineMode,
                            isLoading = isLoading,
                            demoFastReminder = demoFastReminder,
                            onToggleDemoFastReminder = { enabled ->
                                demoFastReminder = enabled
                                sessionManager.setDemoFastReminder(enabled)
                            },
                            onWardSelected = { ward ->
                                selectedWard = ward
                                refreshQueue(forceFull = false)
                            },
                            onRefresh = { refreshQueue(forceFull = true) },
                            onOpenVitalsForm = {
                                editingPatient = null
                                currentScreen = ActiveScreen.VITALS_FORM
                            },
                            onSelectPatient = { patient ->
                                selectedResult = patient
                                currentScreen = ActiveScreen.RESULT
                            },
                            onEditPatient = { patient ->
                                editingPatient = patient
                                currentScreen = ActiveScreen.VITALS_FORM
                            },
                            onDeletePatient = { patient ->
                                scope.launch {
                                    PatientReminderScheduler.cancelReminder(applicationContext, patient.patientId)
                                    val (updated, offline) = repository.deletePatient(serverUrl, patient.patientId)
                                    patients = updated
                                    isOfflineMode = offline
                                }
                            },
                            onLogout = {
                                sessionManager.logout()
                                patients = emptyList()
                                currentScreen = ActiveScreen.AUTH
                            }
                        )
                    }

                    ActiveScreen.VITALS_FORM -> {
                        VitalsFormScreen(
                            initialWard = selectedWard,
                            editingPatient = editingPatient,
                            isSubmitting = isSubmitting,
                            onBack = { currentScreen = ActiveScreen.QUEUE },
                            onSubmitVitals = { vitalsReq ->
                                scope.launch {
                                    isSubmitting = true
                                    val (scoredPatient, offline) = repository.analyzeAndUpdate(serverUrl, vitalsReq)
                                    isOfflineMode = offline
                                    isSubmitting = false
                                    selectedResult = scoredPatient

                                    PatientReminderScheduler.scheduleReminder(
                                        context = applicationContext,
                                        patient = scoredPatient,
                                        demoFastMode = demoFastReminder
                                    )

                                    refreshQueue(forceFull = false)
                                    currentScreen = ActiveScreen.RESULT
                                }
                            }
                        )
                    }

                    ActiveScreen.RESULT -> {
                        val patient = selectedResult
                        if (patient == null) {
                            currentScreen = ActiveScreen.QUEUE
                        } else {
                            AlertResultScreen(
                                patient = patient,
                                isDiagnosed = diagnosedIds.contains(patient.patientId),
                                onBack = { currentScreen = ActiveScreen.QUEUE },
                                onMarkDiagnosed = {
                                    sessionManager.markPatientDiagnosed(patient.patientId)
                                    diagnosedIds = sessionManager.getDiagnosedIds()
                                    PatientReminderScheduler.cancelReminder(applicationContext, patient.patientId)
                                    currentScreen = ActiveScreen.QUEUE
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertResultScreen(
    patient: PatientResponse,
    isDiagnosed: Boolean,
    onBack: () -> Unit,
    onMarkDiagnosed: () -> Unit
) {
    val tier = patient.riskTier.uppercase()
    val (tierColor, tierBg) = when (tier) {
        "RED" -> Pair(Color(0xFFDC2626), Color(0xFFFEF2F2))
        "YELLOW" -> Pair(Color(0xFFD97706), Color(0xFFFFFBEB))
        "UNCERTAIN" -> Pair(Color(0xFF7C3AED), Color(0xFFF5F3FF))
        else -> Pair(Color(0xFF16A34A), Color(0xFFF0FDF4))
    }

    Scaffold(
        containerColor = WardBgRose,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WardBgRose)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WardPrimaryRed)
                }
                Text(
                    text = "Triage Assessment",
                    color = WardPrimaryRed,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = tierBg),
                border = BorderStroke(2.dp, tierColor)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "$tier RISK • ${(patient.riskScore * 100).toInt()}%",
                        color = tierColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${patient.name} • ${patient.ward} (${patient.bed})",
                        color = WardDarkText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = patient.recommendedAction,
                        color = WardDarkText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFFECDD3))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Hemodynamic Indices", fontWeight = FontWeight.Bold, color = WardDarkText)
                    Text("Shock Index (HR / SBP): ${patient.shockIndex}", fontSize = 14.sp)
                    Text("Mean Arterial Pressure (MAP): ${patient.map} mmHg", fontSize = 14.sp)
                    Text("Estimated Onset Window: ~${patient.estimatedHoursToOnset} hrs", fontSize = 14.sp)
                    Text("Next Recheck: Every ${patient.recheckIntervalMins} mins", fontSize = 14.sp)
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFFECDD3))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Clinical Drivers", fontWeight = FontWeight.Bold, color = WardDarkText)
                    patient.topReasons.forEach { reason ->
                        Text("• $reason", fontSize = 13.sp, color = Color(0xFF334155))
                    }
                }
            }

            if (!isDiagnosed) {
                Button(
                    onClick = onMarkDiagnosed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mark Patient Diagnosed (Stop Alerts)", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}