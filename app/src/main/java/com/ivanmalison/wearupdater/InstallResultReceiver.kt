package com.ivanmalison.wearupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "App"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "$label updated", Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Toast.makeText(context, "$label update failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
