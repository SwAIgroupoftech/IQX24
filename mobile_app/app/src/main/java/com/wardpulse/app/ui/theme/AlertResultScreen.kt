package com.wardpulse.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wardpulse.app.model.PatientResponse

@Composable
fun AlertResultScreen(
    result: PatientResponse,
    isDiagnosed: Boolean = false,
    onMarkDiagnosed: () -> Unit = {},
    onBackToQueue: () -> Unit
) {
    val tier = result.riskTier.uppercase()
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
                IconButton(onClick = onBackToQueue) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$tier ALERT • ${(result.riskScore * 100).toInt()}% RISK",
                            color = tierColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (result.isOfflineFallback) {
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "OFFLINE FALLBACK",
                                    color = Color(0xFF92400E),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${result.name} • ${result.ward} (${result.bed})",
                        color = WardDarkText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, tierColor)
                    ) {
                        Text(
                            text = "EST. SEPSIS ONSET WINDOW: ~${result.estimatedHoursToOnset} HRS",
                            color = tierColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFFECDD3))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Shock Index", fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        Text("${result.shockIndex}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = WardDarkText)
                        Text("HR / SBP", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFFECDD3))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Mean Arterial BP", fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        Text("${result.map} mmHg", fontSize = 22.sp, fontWeight = FontWeight.Black, color = WardDarkText)
                        Text("Target ≥ 65", fontSize = 11.sp, color = Color(0xFF94A3B8))
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Clinical Triggers", fontWeight = FontWeight.ExtraBold, color = WardDarkText, fontSize = 15.sp)
                    result.topReasons.forEach { reason ->
                        Text("• $reason", fontSize = 13.sp, color = Color(0xFF334155), fontWeight = FontWeight.Medium)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, tierColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Recommended Action", fontWeight = FontWeight.ExtraBold, color = tierColor, fontSize = 14.sp)
                    Text(result.recommendedAction, fontSize = 14.sp, color = WardDarkText, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Reminder Alarm Scheduled: Every ${result.recheckIntervalMins} mins",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                }
            }

            if (!isDiagnosed) {
                Button(
                    onClick = onMarkDiagnosed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mark Patient Diagnosed (Stop Alerts)", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            UiverseActionButton(
                text = "Back to Ward Urgency Queue",
                onClick = onBackToQueue,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}