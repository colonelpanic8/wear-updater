package com.ivanmalison.wearupdater

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rows = mutableMapOf<String, AppRow>()
    private val resolved = mutableMapOf<String, ReleaseDescriptor>()
    private lateinit var checkButton: Button
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        UpdateCheckWorker.schedule(this)
        requestNotificationPermission()
        checkForUpdates()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(30))
            setBackgroundColor(Color.BLACK)
        }
        content.addView(text("Wear Updater", 20f, Color.WHITE).apply { gravity = Gravity.CENTER })
        summary = text("Checking signed releases…", 13f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(summary)

        UpdateCatalog.sources.forEach { source ->
            val label = text(source.label, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }
            val status = text("Not checked", 12f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
            val install = Button(this).apply {
                text = "Install"
                visibility = View.GONE
                setOnClickListener { resolved[source.packageName]?.let(::downloadAndInstall) }
            }
            content.addView(label)
            content.addView(status)
            content.addView(install)
            rows[source.packageName] = AppRow(status, install)
        }

        checkButton = Button(this).apply {
            text = "Check again"
            setOnClickListener { checkForUpdates() }
        }
        content.addView(checkButton)
        return ScrollView(this).apply { addView(content) }
    }

    private fun checkForUpdates() {
        checkButton.isEnabled = false
        summary.text = "Checking signed releases…"
        resolved.clear()
        rows.values.forEach { row ->
            row.status.text = "Checking…"
            row.install.visibility = View.GONE
        }
        executor.execute {
            val results = UpdateCatalog.check(this)
            mainHandler.post {
                var available = 0
                results.forEachIndexed { index, result ->
                    val source = UpdateCatalog.sources[index]
                    val row = rows.getValue(source.packageName)
                    result.onSuccess { state ->
                        val installed = state.installed?.let { "installed ${it.versionName}" } ?: "not installed"
                        row.status.text = if (state.updateAvailable) {
                            available += 1
                            resolved[source.packageName] = state.release
                            "${state.release.versionName} available · $installed"
                        } else {
                            "Current · ${state.installed?.versionName}"
                        }
                        row.install.text = if (state.installed == null) "Install" else "Update"
                        row.install.visibility = if (state.updateAvailable) View.VISIBLE else View.GONE
                    }.onFailure { error ->
                        row.status.text = "Check failed: ${error.message}"
                    }
                }
                summary.text = when (available) {
                    0 -> "Everything is current"
                    1 -> "1 update available"
                    else -> "$available updates available"
                }
                checkButton.isEnabled = true
            }
        }
    }

    private fun downloadAndInstall(release: ReleaseDescriptor) {
        val row = rows.getValue(release.packageName)
        row.install.isEnabled = false
        row.status.text = "Downloading ${release.versionName}…"
        executor.execute {
            val apk = File(cacheDir, "${release.packageName}-${release.versionName}.apk")
            runCatching {
                Network.download(release, apk)
                ApkInstaller.install(this, release, apk)
            }.onSuccess {
                mainHandler.post { row.status.text = "Confirm the update on the watch" }
            }.onFailure { error ->
                mainHandler.post {
                    row.status.text = "Install failed: ${error.message}"
                    row.install.isEnabled = true
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class AppRow(val status: TextView, val install: Button)
}
