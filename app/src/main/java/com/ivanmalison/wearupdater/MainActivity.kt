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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rows = mutableMapOf<String, AppRow>()
    private val available = mutableMapOf<String, ReleaseDescriptor>()
    private lateinit var summary: TextView
    private lateinit var overall: ProgressBar
    private lateinit var updateAllButton: Button
    private lateinit var checkButton: Button

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
            setPadding(dp(18), dp(24), dp(18), dp(30))
            setBackgroundColor(Color.BLACK)
        }
        content.addView(text("Wear Updater", 20f, Color.WHITE))
        summary = text("Checking signed releases…", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(summary)

        overall = progressBar().apply { visibility = View.GONE }
        content.addView(overall, barLayout())

        updateAllButton = Button(this).apply {
            text = "Update all"
            visibility = View.GONE
            setOnClickListener { install(orderedUpdates()) }
        }
        content.addView(updateAllButton)

        UpdateCatalog.sources.forEach { source ->
            content.addView(divider(), dividerLayout())
            content.addView(text(source.label, 16f, Color.WHITE))
            val status = text("Not checked", 12f, MUTED)
            val bar = progressBar().apply { visibility = View.GONE }
            val action = Button(this).apply {
                text = "Install"
                visibility = View.GONE
                setOnClickListener {
                    available[source.packageName]?.let { install(listOf(it)) }
                }
            }
            content.addView(status)
            content.addView(bar, barLayout())
            content.addView(action)
            rows[source.packageName] = AppRow(status, bar, action)
        }

        content.addView(divider(), dividerLayout())
        checkButton = Button(this).apply {
            text = "Check again"
            setOnClickListener { checkForUpdates() }
        }
        content.addView(checkButton)
        return ScrollView(this).apply {
            addView(content)
            isFocusableInTouchMode = true
            requestFocus()
            setOnGenericMotionListener { view, event -> scrollByRotary(view as ScrollView, event) }
        }
    }

    private fun scrollByRotary(view: ScrollView, event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            return false
        }
        val factor = ViewConfiguration.get(this).scaledVerticalScrollFactor
        view.scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor).roundToInt())
        return true
    }

    private fun checkForUpdates() {
        setBusy(true)
        available.clear()
        rows.values.forEach { row ->
            row.action.visibility = View.GONE
            row.status.setTextColor(MUTED)
            row.status.text = "Checking…"
            show(row.bar, Progress.Busy)
        }
        val total = UpdateCatalog.sources.size
        summary.text = "Checking 0 of $total…"
        show(overall, Progress.Percent(0))
        executor.execute {
            var checked = 0
            UpdateCatalog.check(this) { index, result ->
                checked += 1
                val done = checked
                mainHandler.post {
                    applyCheckResult(UpdateCatalog.sources[index], result)
                    summary.text = "Checking $done of $total…"
                    show(overall, Progress.Percent(done * 100 / total))
                }
            }
            mainHandler.post {
                show(overall, Progress.Hidden)
                summary.text = when (val count = available.size) {
                    0 -> "Everything is current"
                    1 -> "1 update available"
                    else -> "$count updates available"
                }
                setBusy(false)
            }
        }
    }

    private fun applyCheckResult(source: UpdateSource, result: Result<UpdateState>) {
        val row = rows.getValue(source.packageName)
        show(row.bar, Progress.Hidden)
        result.onSuccess { state ->
            if (state.updateAvailable) {
                available[source.packageName] = state.release
                val installed = state.installed?.let { "from ${it.versionName}" } ?: "not installed"
                row.status.setTextColor(ACCENT)
                row.status.text = "${state.release.versionName} · $installed"
                row.action.text = if (state.installed == null) "Install" else "Update"
                row.action.visibility = View.VISIBLE
            } else {
                row.status.setTextColor(OK)
                row.status.text = "Current · ${state.installed?.versionName}"
                row.action.visibility = View.GONE
            }
        }.onFailure { error ->
            row.status.setTextColor(FAILED)
            row.status.text = "Check failed · ${describe(error)}"
            row.action.visibility = View.GONE
        }
    }

    private fun orderedUpdates(): List<ReleaseDescriptor> = installOrder(
        UpdateCatalog.sources.mapNotNull { available[it.packageName] },
        packageName,
    )

    private fun install(releases: List<ReleaseDescriptor>) {
        if (releases.isEmpty()) return
        setBusy(true)
        releases.forEach { release ->
            val row = rows.getValue(release.packageName)
            row.action.visibility = View.GONE
            row.status.setTextColor(MUTED)
            row.status.text = if (releases.size > 1) "Queued" else "Starting…"
        }
        show(overall, Progress.Percent(0))
        executor.execute {
            var failed = 0
            releases.forEachIndexed { index, release ->
                mainHandler.post {
                    summary.text = if (releases.size > 1) {
                        "Updating ${index + 1} of ${releases.size} · ${release.label}"
                    } else {
                        "Updating ${release.label}"
                    }
                    show(overall, Progress.Percent(index * 100 / releases.size))
                }
                if (!installOne(release)) failed += 1
            }
            val failures = failed
            mainHandler.post {
                show(overall, Progress.Hidden)
                summary.text = when {
                    failures == 0 && releases.size == 1 -> "${releases.first().label} is up to date"
                    failures == 0 -> "Updated ${releases.size} apps"
                    else -> "$failures of ${releases.size} failed"
                }
                setBusy(false)
            }
        }
    }

    /** Runs on [executor]; returns true when the app ended up installed. */
    private fun installOne(release: ReleaseDescriptor): Boolean {
        val apk = File(cacheDir, "${release.packageName}-${release.versionName}.apk")
        return InstallEvents.watch(release.packageName).use { watch ->
            runCatching {
                Network.download(
                    release = release,
                    destination = apk,
                    onProgress = { completed, total ->
                        report(
                            release,
                            if (total > 0) {
                                "Downloading · ${formatBytes(completed)} of ${formatBytes(total)}"
                            } else {
                                "Downloading · ${formatBytes(completed)}"
                            },
                            if (total > 0) Progress.Percent((completed * 100 / total).toInt()) else Progress.Busy,
                        )
                    },
                    onRetry = { attempt, error ->
                        report(release, "Connection lost · retry $attempt ($error)", Progress.Busy, FAILED)
                    },
                )
                report(release, "Verifying signature…", Progress.Busy)
                ApkInstaller.install(this, release, apk)
                report(release, "Installing ${release.versionName}…", Progress.Busy)
                watch.awaitOutcome(INSTALL_TIMEOUT_MILLIS) {
                    report(release, "Confirm the update on the watch", Progress.Busy, ACCENT)
                } ?: error("Timed out waiting for the installer")
            }.fold(
                onSuccess = { event ->
                    val succeeded = event.outcome == InstallEvents.Outcome.SUCCESS
                    if (succeeded) {
                        apk.delete()
                        mainHandler.post { available.remove(release.packageName) }
                        report(release, "Installed ${release.versionName}", Progress.Hidden, OK)
                    } else {
                        report(release, "Install failed · ${event.message}", Progress.Hidden, FAILED, retryable = true)
                    }
                    succeeded
                },
                onFailure = { error ->
                    report(release, "Failed · ${describe(error)}", Progress.Hidden, FAILED, retryable = true)
                    false
                },
            )
        }
    }

    private fun report(
        release: ReleaseDescriptor,
        detail: String,
        progress: Progress,
        color: Int = MUTED,
        retryable: Boolean = false,
    ) {
        mainHandler.post {
            val row = rows[release.packageName] ?: return@post
            row.status.setTextColor(color)
            row.status.text = detail
            show(row.bar, progress)
            if (retryable) {
                row.action.text = "Retry"
                row.action.visibility = View.VISIBLE
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        checkButton.isEnabled = !busy
        rows.values.forEach { it.action.isEnabled = !busy }
        val count = available.size
        updateAllButton.isEnabled = !busy
        updateAllButton.text = if (count > 1) "Update all ($count)" else "Update all"
        updateAllButton.visibility = if (!busy && count > 1) View.VISIBLE else View.GONE
    }

    private fun show(bar: ProgressBar, progress: Progress) {
        when (progress) {
            Progress.Hidden -> bar.visibility = View.GONE
            Progress.Busy -> {
                bar.isIndeterminate = true
                bar.visibility = View.VISIBLE
            }
            is Progress.Percent -> {
                bar.isIndeterminate = false
                bar.progress = progress.value.coerceIn(0, 100)
                bar.visibility = View.VISIBLE
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
        gravity = Gravity.CENTER
    }

    private fun progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
    }

    private fun barLayout() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.DKGRAY) }

    private fun dividerLayout() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(10)
        bottomMargin = dp(10)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class AppRow(val status: TextView, val bar: ProgressBar, val action: Button)

    private sealed class Progress {
        object Hidden : Progress()
        object Busy : Progress()
        data class Percent(val value: Int) : Progress()
    }

    private companion object {
        const val INSTALL_TIMEOUT_MILLIS = 3 * 60 * 1000L
        const val MUTED = Color.LTGRAY
        const val OK = 0xFF7ED957.toInt()
        const val ACCENT = 0xFFFFC24B.toInt()
        const val FAILED = 0xFFFF6B6B.toInt()
    }
}
