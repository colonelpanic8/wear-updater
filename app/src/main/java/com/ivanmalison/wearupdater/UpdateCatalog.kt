package com.ivanmalison.wearupdater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseDescriptor(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long?,
    val downloadUrl: String,
    val sha256: String?,
    val certificateSha256: String,
)

data class InstalledVersion(val versionName: String, val versionCode: Long)

data class UpdateState(
    val release: ReleaseDescriptor,
    val installed: InstalledVersion?,
    val updateAvailable: Boolean,
)

sealed class UpdateSource(
    val packageName: String,
    val label: String,
    val certificateSha256: String,
) {
    abstract fun resolve(): ReleaseDescriptor
}

class GitHubReleaseSource(
    packageName: String,
    label: String,
    certificateSha256: String,
    private val repository: String,
    private val assetName: String,
    private val tagVersionCode: (String) -> Long? = { null },
) : UpdateSource(packageName, label, certificateSha256) {
    override fun resolve(): ReleaseDescriptor {
        val json = Network.getJson("https://api.github.com/repos/$repository/releases/latest")
        return parseGitHubRelease(
            json = json,
            packageName = packageName,
            label = label,
            assetName = assetName,
            certificateSha256 = certificateSha256,
            tagVersionCode = tagVersionCode,
        )
    }
}

class JsonManifestSource(
    packageName: String,
    label: String,
    certificateSha256: String,
    private val manifestUrl: String,
) : UpdateSource(packageName, label, certificateSha256) {
    override fun resolve(): ReleaseDescriptor = parseUpdateManifest(
        json = Network.getJson(manifestUrl),
        expectedPackageName = packageName,
        label = label,
        expectedCertificateSha256 = certificateSha256,
    )
}

object UpdateCatalog {
    val sources: List<UpdateSource> = listOf(
        GitHubReleaseSource(
            packageName = "com.colonelpanic.mova",
            label = "Mova",
            certificateSha256 = "905afc8729daa77fff81b20d99b169f919879379a8e7dbe23546e350ed46ad22",
            repository = "colonelpanic8/mova",
            assetName = "wear-release.apk",
        ),
        GitHubReleaseSource(
            packageName = "com.ivanmalison.akuvoxwear",
            label = "SmartPlus Unlock",
            certificateSha256 = "0c3e768a3b8d45e485ee5ac7eb86bab1d7f9842347096dd2205b82b1078e0b5d",
            repository = "colonelpanic8/akuvox-wear",
            assetName = "smartplus-wear-watch.apk",
            tagVersionCode = ::akuvoxVersionCode,
        ),
        JsonManifestSource(
            packageName = "sh.paseo.assembly",
            label = "Paseo",
            certificateSha256 = "8d229a78b0d7c22086e743c9edc3678b5ed7e9c5388aeb18013729568139b8ee",
            manifestUrl = "https://colonelpanic8.github.io/paseo-assembly-fdroid/watch/latest.json",
        ),
        GitHubReleaseSource(
            packageName = "com.ivanmalison.translatewear",
            label = "Live Translate",
            certificateSha256 = "b291e1426fdb798c6a0463126fed57f6e8273ca450b67fb0eeb5eac64e4fb727",
            repository = "colonelpanic8/translate-wear",
            assetName = "translate-wear-watch.apk",
        ),
        GitHubReleaseSource(
            packageName = "com.ivanmalison.tilewear",
            label = "Tile Wear",
            certificateSha256 = "83b1c3b1c4f4feb78e32f82cd674a212403bab89517fcf0d162f4ce5cb61bd74",
            repository = "colonelpanic8/tile-wear",
            assetName = "tile-wear-watch.apk",
        ),
        GitHubReleaseSource(
            packageName = "com.ivanmalison.wearupdater",
            label = "Wear Updater",
            certificateSha256 = "0adabe7ec93bf85d97160a7910d849f729642259fe5eeb6930e1dc8066f910eb",
            repository = "colonelpanic8/wear-updater",
            assetName = "wear-updater.apk",
        ),
    )

    /** [onResult] fires as each source resolves so callers can show progress instead of a long blank wait. */
    fun check(
        context: Context,
        onResult: (index: Int, result: Result<UpdateState>) -> Unit = { _, _ -> },
    ): List<Result<UpdateState>> = sources.mapIndexed { index, source ->
        runCatching {
            val release = source.resolve()
            val installed = installedVersion(context.packageManager, source.packageName)
            UpdateState(release, installed, isNewer(release, installed))
        }.also { onResult(index, it) }
    }
}

/**
 * Installing the updater replaces this process, so it goes last and every other queued app
 * gets installed first.
 */
fun installOrder(releases: List<ReleaseDescriptor>, selfPackageName: String): List<ReleaseDescriptor> =
    releases.sortedBy { if (it.packageName == selfPackageName) 1 else 0 }

fun parseGitHubRelease(
    json: String,
    packageName: String,
    label: String,
    assetName: String,
    certificateSha256: String,
    tagVersionCode: (String) -> Long? = { null },
): ReleaseDescriptor {
    val root = JSONObject(json)
    val tag = root.getString("tag_name")
    val assets = root.getJSONArray("assets")
    val asset = (0 until assets.length())
        .map(assets::getJSONObject)
        .firstOrNull { it.getString("name") == assetName }
        ?: error("$assetName is missing from release $tag")
    val digest = asset.optString("digest")
        .takeIf { it.startsWith("sha256:") }
        ?.removePrefix("sha256:")
    return ReleaseDescriptor(
        packageName = packageName,
        label = label,
        versionName = tag.removePrefix("v"),
        versionCode = tagVersionCode(tag),
        downloadUrl = asset.getString("browser_download_url"),
        sha256 = digest,
        certificateSha256 = certificateSha256,
    )
}

fun parseUpdateManifest(
    json: String,
    expectedPackageName: String,
    label: String,
    expectedCertificateSha256: String,
): ReleaseDescriptor {
    val root = JSONObject(json)
    require(root.getString("packageName") == expectedPackageName) { "Unexpected package in update manifest" }
    val certificate = root.getString("certificateSha256").lowercase()
    require(certificate == expectedCertificateSha256) { "Unexpected certificate in update manifest" }
    return ReleaseDescriptor(
        packageName = expectedPackageName,
        label = label,
        versionName = root.getString("versionName"),
        versionCode = root.getLong("versionCode"),
        downloadUrl = root.getString("url"),
        sha256 = root.optString("sha256").ifBlank { null },
        certificateSha256 = expectedCertificateSha256,
    )
}

fun akuvoxVersionCode(tag: String): Long? = Regex("wear\\.(\\d+)$")
    .find(tag)
    ?.groupValues
    ?.get(1)
    ?.toLong()

fun isNewer(release: ReleaseDescriptor, installed: InstalledVersion?): Boolean {
    if (installed == null) return true
    release.versionCode?.let { return it > installed.versionCode }
    return compareSemanticVersions(release.versionName, installed.versionName) > 0
}

fun compareSemanticVersions(left: String, right: String): Int {
    fun components(value: String) = value.removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
    val a = components(left)
    val b = components(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

@Suppress("DEPRECATION")
fun installedVersion(packageManager: PackageManager, packageName: String): InstalledVersion? = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
    InstalledVersion(info.versionName.orEmpty(), if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
} catch (_: PackageManager.NameNotFoundException) {
    null
}

object Network {
    private const val MAX_ATTEMPTS = 4
    private const val PROGRESS_INTERVAL_MILLIS = 150L

    fun getJson(url: String): String = withRetry {
        val connection = open(url)
        try {
            connection.inputStream.reader().readText()
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        release: ReleaseDescriptor,
        destination: File,
        onProgress: (completed: Long, total: Long) -> Unit = { _, _ -> },
        onRetry: (attempt: Int, error: String) -> Unit = { _, _ -> },
    ) {
        withRetry(onRetry) {
            val resumeFrom = if (destination.exists()) destination.length() else 0L
            val connection = open(url = release.downloadUrl, rangeStart = resumeFrom, allowRangeRefusal = true)
            try {
                if (connection.responseCode != HTTP_RANGE_NOT_SATISFIABLE) {
                    val resuming = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                    val start = if (resuming) resumeFrom else 0L
                    val total = connection.contentLengthLong.takeIf { it >= 0 }?.plus(start) ?: -1L
                    var completed = start
                    onProgress(completed, total)
                    connection.inputStream.use { input ->
                        FileOutputStream(destination, resuming).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var reportedAt = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                completed += count
                                val now = System.currentTimeMillis()
                                if (now - reportedAt >= PROGRESS_INTERVAL_MILLIS) {
                                    reportedAt = now
                                    onProgress(completed, total)
                                }
                            }
                            output.flush()
                        }
                    }
                    onProgress(completed, total)
                }
            } finally {
                connection.disconnect()
            }
        }

        release.sha256?.let { expected ->
            val actual = sha256(destination)
            if (actual != expected.lowercase()) {
                destination.delete()
                error("Downloaded APK digest does not match its release metadata")
            }
        }
    }

    /**
     * Watch networking drops sockets often enough ("software caused connection abort") that a
     * single failure should not end an install. Partial downloads resume via Range when the
     * origin honours it, and restart from zero when it does not.
     */
    private fun <T> withRetry(
        onRetry: (attempt: Int, error: String) -> Unit = { _, _ -> },
        block: () -> T,
    ): T {
        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return block()
            } catch (error: IOException) {
                lastError = error
                if (attempt == MAX_ATTEMPTS) break
                onRetry(attempt, describe(error))
                Thread.sleep(backoffMillis(attempt))
            }
        }
        throw lastError ?: IllegalStateException("Retry loop ended without a result")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(
        url: String,
        rangeStart: Long = 0L,
        allowRangeRefusal: Boolean = false,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Ivan-Wear-Updater")
        if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        connect()
        val acceptable = responseCode in 200..299 ||
            (allowRangeRefusal && responseCode == HTTP_RANGE_NOT_SATISFIABLE)
        if (!acceptable) {
            val status = responseCode
            val host = this.url.host
            disconnect()
            if (isTransient(status)) throw IOException("HTTP $status from $host")
            error("HTTP $status from $host")
        }
    }

    private fun isTransient(status: Int): Boolean = status >= 500 || status == 429 || status == 408

    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}

fun backoffMillis(attempt: Int): Long = minOf(1_000L shl (attempt - 1), 8_000L)

/** [IOException.message] is often null or a bare class name, which reads badly on a watch. */
fun describe(error: Throwable): String =
    error.message?.takeUnless(String::isBlank) ?: error.javaClass.simpleName

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
