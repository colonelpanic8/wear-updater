package com.ivanmalison.wearupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(ApkInstaller.EXTRA_LABEL) ?: "App"
        val packageName = intent.getStringExtra(ApkInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        val outcome = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallEvents.Outcome.PENDING_USER_ACTION
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.Outcome.SUCCESS
            else -> InstallEvents.Outcome.FAILURE
        }
        if (outcome == InstallEvents.Outcome.PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) context.startActivity(confirmation)
        }

        val observed = InstallEvents.publish(
            InstallEvents.Event(packageName, label, outcome, message ?: "status $status"),
        )
        if (observed) return

        when (outcome) {
            InstallEvents.Outcome.PENDING_USER_ACTION -> Unit
            InstallEvents.Outcome.SUCCESS -> Toast.makeText(context, "$label updated", Toast.LENGTH_LONG).show()
            InstallEvents.Outcome.FAILURE ->
                Toast.makeText(context, "$label update failed: ${message ?: "status $status"}", Toast.LENGTH_LONG).show()
        }
    }
}
