package com.wardpulse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wardpulse.app.model.PatientResponse
import com.wardpulse.app.network.WardRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WardQueueScreen(
    hospitalName: String,
    patients: List<PatientResponse>,
    diagnosedIds: Set<String>,
    selectedWard: String,
    isOfflineMode: Boolean,
    isLoading: Boolean,
    demoFastReminder: Boolean,
    onToggleDemoFastReminder: (Boolean) -> Unit,
    onWardSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenVitalsForm: () -> Unit,
    onSelectPatient: (PatientResponse) -> Unit,
    onEditPatient: (PatientResponse) -> Unit,
    onDeletePatient: (PatientResponse) -> Unit,
    onLogout: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var actionTargetPatient by remember { mutableStateOf<PatientResponse?>(null) }

    val activePatients = remember(patients, diagnosedIds) {
        patients.filter { it.patientId !in diagnosedIds }
    }
    val diagnosedPatients = remember(patients, diagnosedIds) {
        patients.filter { it.patientId in diagnosedIds }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.White,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WardPrimaryRed)
                        .statusBarsPadding()
                        .padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalHospital,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = hospitalName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Ward & Triage Navigation",
                        color = Color(0xFFFFE4E6),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "WARDS",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                WardRepository.WARDS.forEach { ward ->
                    val label = if (ward == "ALL") "All Wards" else ward
                    NavigationDrawerItem(
                        label = { Text(label, fontWeight = FontWeight.Bold) },
                        selected = selectedWard == ward,
                        onClick = {
                            onWardSelected(ward)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color(0xFFFFE4E6),
                            selectedTextColor = WardPrimaryRed
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("1-Min Demo Alarm", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WardDarkText)
                        Text("Fast repeat alert for demo", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Switch(
                        checked = demoFastReminder,
                        onCheckedChange = onToggleDemoFastReminder,
                        colors = SwitchDefaults.colors(checkedTrackColor = WardPrimaryRed)
                    )
                }

                if (diagnosedPatients.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        text = "DIAGNOSED PEOPLE (${diagnosedPatients.size})",
                        color = Color(0xFF15803D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    diagnosedPatients.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${p.name} (${p.ward} • ${p.bed})",
                                fontSize = 12.sp,
                                color = WardDarkText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = WardPrimaryRed) },
                    label = { Text("Logout", color = WardPrimaryRed, fontWeight = FontWeight.Bold) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    },
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    ) {
        Scaffold(
            containerColor = WardBgRose,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WardBgRose)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Select Ward",
                                tint = WardPrimaryRed,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (selectedWard == "ALL") "All Wards" else selectedWard,
                                color = WardPrimaryRed,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = hospitalName,
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (isOfflineMode) Color(0xFFFEF3C7) else Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isOfflineMode) Color(0xFFD97706) else Color(0xFF16A34A))
                        ) {
                            Text(
                                text = if (isOfflineMode) "OFFLINE SAFE" else "CONNECTED",
                                color = if (isOfflineMode) Color(0xFF92400E) else Color(0xFF166534),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = WardPrimaryRed)
                        }
                    }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onOpenVitalsForm,
                    containerColor = WardPrimaryRed,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Check Vitals")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check Vitals", fontWeight = FontWeight.Bold)
                }
            }
        ) { innerPadding ->
            if (isLoading && activePatients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = WardPrimaryRed)
                }
            } else if (activePatients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No Active Patients in Queue",
                            color = WardDarkText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap '+ Check Vitals' below to enter bedside vitals and score risk.",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 92.dp)
                ) {
                    items(activePatients, key = { it.patientId }) { patient ->
                        PatientPriorityCard(
                            patient = patient,
                            onClick = { onSelectPatient(patient) },
                            onLongClick = { actionTargetPatient = patient }
                        )
                    }
                }
            }
        }
    }

    if (actionTargetPatient != null) {
        val target = actionTargetPatient!!
        AlertDialog(
            onDismissRequest = { actionTargetPatient = null },
            title = { Text("${target.name} (${target.bed})", fontWeight = FontWeight.Bold) },
            text = { Text("Choose an action for this patient record:") },
            confirmButton = {
                TextButton(onClick = {
                    actionTargetPatient = null
                    onEditPatient(target)
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit Vitals", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    actionTargetPatient = null
                    onDeletePatient(target)
                }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = WardPrimaryRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = WardPrimaryRed, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatientPriorityCard(
    patient: PatientResponse,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tier = patient.riskTier.uppercase()
    val (tierColor, tierBg, borderColor) = when (tier) {
        "RED" -> Triple(Color(0xFFDC2626), Color(0xFFFEF2F2), Color(0xFFFCA5A5))
        "YELLOW" -> Triple(Color(0xFFD97706), Color(0xFFFFFBEB), Color(0xFFFCD34D))
        "UNCERTAIN" -> Triple(Color(0xFF7C3AED), Color(0xFFF5F3FF), Color(0xFFC4B5FD))
        else -> Triple(Color(0xFF16A34A), Color(0xFFF0FDF4), Color(0xFF86EFAC))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(tierColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = patient.name,
                            color = WardDarkText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${patient.ward} • ${patient.bed} • ID: ${patient.patientId}",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    color = tierBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Text(
                        text = if (tier == "UNCERTAIN") "RE-CHECK" else "$tier • ${(patient.riskScore * 100).toInt()}%",
                        color = tierColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = tierColor, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (tier == "GREEN") "Stable" else "Onset ~${patient.estimatedHoursToOnset}h",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WardDarkText
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Recheck every ${patient.recheckIntervalMins}m",
                        fontSize = 12.sp,
                        color = Color(0xFF475569),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (patient.topReasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = patient.topReasons.first(),
                    color = Color(0xFF334155),
                    fontSize = 12.sp,
                    maxLines = 2
                )
            }
        }
    }
}