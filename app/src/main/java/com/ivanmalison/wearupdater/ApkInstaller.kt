package com.ivanmalison.wearupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object ApkInstaller {
    const val ACTION_INSTALL_RESULT = "com.ivanmalison.wearupdater.INSTALL_RESULT"

    fun install(context: Context, release: ReleaseDescriptor, apk: File) {
        require(context.packageManager.canRequestPackageInstalls()) {
            "The one-time installer permission has not been granted"
        }
        val archive = archiveInfo(context.packageManager, apk)
            ?: error("The downloaded file is not an APK")
        require(archive.packageName == release.packageName) { "The APK has an unexpected package name" }
        require(signerFingerprints(archive).contains(release.certificateSha256)) {
            "The APK signer is not trusted"
        }
        release.versionCode?.let { expected ->
            require(versionCode(archive) == expected) { "The APK version differs from the update metadata" }
        }

        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(release.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val resultIntent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_RESULT
                putExtra("label", release.label)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(packageManager: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(apk.path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerFingerprints(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
