package com.wardpulse.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.wardpulse.app.model.PatientResponse
import java.util.concurrent.TimeUnit

object PatientReminderScheduler {

    private const val CHANNEL_ID = "wardpulse_alerts"

    fun scheduleReminder(context: Context, patient: PatientResponse, demoFastMode: Boolean) {
        val delayMinutes = if (demoFastMode) 1L else patient.recheckIntervalMins.toLong().coerceAtLeast(15L)

        val inputData = workDataOf(
            "patient_id" to patient.patientId,
            "name" to patient.name,
            "ward" to patient.ward,
            "bed" to patient.bed,
            "risk_tier" to patient.riskTier
        )

        val workRequest = PeriodicWorkRequestBuilder<PatientReminderWorker>(
            repeatInterval = delayMinutes.coerceAtLeast(15L),
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(inputData)
            .addTag("reminder_${patient.patientId}")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wardpulse_work_${patient.patientId}",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelReminder(context: Context, patientId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("wardpulse_work_$patientId")
    }

    fun showImmediateNotification(context: Context, title: String, message: String, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WardPulse Triage Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent sepsis escalation & 4-hour vitals checkup alarms"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(notificationId, notification)
    }
}

class PatientReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val patientId = inputData.getString("patient_id") ?: return Result.failure()
        val name = inputData.getString("name") ?: "Patient"
        val ward = inputData.getString("ward") ?: "Ward"
        val bed = inputData.getString("bed") ?: "Bed"
        val tier = inputData.getString("risk_tier") ?: "CHECK"

        PatientReminderScheduler.showImmediateNotification(
            context = applicationContext,
            title = "WardPulse [$tier]: Vitals Recheck Due",
            message = "$name ($ward • $bed) is due for bedside vitals check.",
            notificationId = patientId.hashCode()
        )
        return Result.success()
    }
}