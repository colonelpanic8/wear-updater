package com.ivanmalison.wearupdater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        val updates = UpdateCatalog.check(applicationContext)
            .mapNotNull { it.getOrNull() }
            .filter(UpdateState::updateAvailable)
        if (updates.isNotEmpty()) notify(updates)
        Result.success()
    }.getOrElse { Result.retry() }

    private fun notify(updates: List<UpdateState>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val labels = updates.joinToString { it.release.label }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Wear app updates available")
            .setContentText(labels)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "wear-app-updates"
        private const val NOTIFICATION_ID = 4107

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "wear-app-update-check",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
