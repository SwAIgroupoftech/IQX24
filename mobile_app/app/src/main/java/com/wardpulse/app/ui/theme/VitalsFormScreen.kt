package com.wardpulse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wardpulse.app.model.PatientResponse
import com.wardpulse.app.model.VitalsRequest
import com.wardpulse.app.network.WardRepository
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsFormScreen(
    initialWard: String,
    editingPatient: PatientResponse?,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onSubmitVitals: (VitalsRequest) -> Unit
) {
    val defaultWard = if (initialWard == "ALL") "General Ward A" else initialWard
    var patientId by remember {
        mutableStateOf(
            editingPatient?.patientId ?: "P-${UUID.randomUUID().toString().take(6).uppercase()}"
        )
    }
    var name by remember { mutableStateOf(editingPatient?.name ?: "") }
    var ward by remember { mutableStateOf(editingPatient?.ward ?: defaultWard) }
    var bed by remember { mutableStateOf(editingPatient?.bed ?: "") }
    var age by remember { mutableStateOf("54") }
    var hr by remember { mutableStateOf("") }
    var sbp by remember { mutableStateOf("") }
    var dbp by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf("") }
    var resp by remember { mutableStateOf("") }
    var o2sat by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var wardMenuExpanded by remember { mutableStateOf(false) }

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
                    text = if (editingPatient != null) "Update Patient Vitals" else "Check Patient Vitals",
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFFECDD3))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Patient Details", fontWeight = FontWeight.Bold, color = WardDarkText, fontSize = 15.sp)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Patient Full Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = wardMenuExpanded,
                        onExpandedChange = { wardMenuExpanded = !wardMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = ward,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Ward") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wardMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = wardMenuExpanded,
                            onDismissRequest = { wardMenuExpanded = false }
                        ) {
                            WardRepository.WARDS.filter { it != "ALL" }.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text(w) },
                                    onClick = {
                                        ward = w
                                        wardMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = bed,
                            onValueChange = { bed = it },
                            label = { Text("Bed No.") },
                            placeholder = { Text("Bed 4") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = age,
                            onValueChange = { age = it },
                            label = { Text("Age") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFFECDD3))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("6 Bedside Vitals", fontWeight = FontWeight.Bold, color = WardDarkText, fontSize = 15.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VitalInput("Heart Rate (bpm)", hr, { hr = it }, Modifier.weight(1f))
                        VitalInput("Resp Rate (/min)", resp, { resp = it }, Modifier.weight(1f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VitalInput("Systolic BP", sbp, { sbp = it }, Modifier.weight(1f))
                        VitalInput("Diastolic BP", dbp, { dbp = it }, Modifier.weight(1f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VitalInput("Temp (°C)", temp, { temp = it }, Modifier.weight(1f))
                        VitalInput("SpO2 (%)", o2sat, { o2sat = it }, Modifier.weight(1f))
                    }
                }
            }

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    color = Color(0xFFDC2626),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    val hrVal = hr.toDoubleOrNull()
                    val sbpVal = sbp.toDoubleOrNull()
                    val dbpVal = dbp.toDoubleOrNull()
                    val tempVal = temp.toDoubleOrNull()
                    val respVal = resp.toDoubleOrNull()
                    val o2Val = o2sat.toDoubleOrNull()
                    val ageVal = age.toIntOrNull() ?: 50

                    if (name.isBlank() || bed.isBlank() || hrVal == null || sbpVal == null ||
                        dbpVal == null || tempVal == null || respVal == null || o2Val == null
                    ) {
                        validationError = "Please fill in patient name, bed, and all 6 numeric vitals."
                        return@Button
                    }
                    validationError = null
                    onSubmitVitals(
                        VitalsRequest(
                            patientId = patientId,
                            name = name.trim(),
                            ward = ward,
                            bed = bed.trim(),
                            age = ageVal,
                            hr = hrVal,
                            sbp = sbpVal,
                            dbp = dbpVal,
                            temp = tempVal,
                            resp = respVal,
                            o2sat = o2Val
                        )
                    )
                },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WardPrimaryRed)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Text("Run Sepsis & Risk Triage", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VitalInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    )
}