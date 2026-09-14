package com.ivanmalison.wearupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCatalogTest {
    @Test
    fun parsesGitHubReleaseAsset() {
        val release = parseGitHubRelease(
            json = """{"tag_name":"v6.23.1","assets":[{"name":"wear-release.apk","browser_download_url":"https://example.test/app.apk","digest":"sha256:abc123"}]}""",
            packageName = "example.app",
            label = "Example",
            assetName = "wear-release.apk",
            certificateSha256 = "cert",
        )
        assertEquals("6.23.1", release.versionName)
        assertEquals("https://example.test/app.apk", release.downloadUrl)
        assertEquals("abc123", release.sha256)
    }

    @Test
    fun parsesAkuvoxBuildFromTag() {
        assertEquals(3L, akuvoxVersionCode("v7.50.0003-wear.3"))
        assertEquals(null, akuvoxVersionCode("v7.50.0003"))
    }

    @Test
    fun parsesPaseoManifest() {
        val release = parseUpdateManifest(
            json = """{"packageName":"sh.paseo.assembly","versionCode":1000001935,"versionName":"0.7.0-assembly.193","url":"https://example.test/paseo.apk","sha256":"beef","certificateSha256":"cafe"}""",
            expectedPackageName = "sh.paseo.assembly",
            label = "Paseo",
            expectedCertificateSha256 = "cafe",
        )
        assertEquals(1000001935L, release.versionCode)
        assertEquals("beef", release.sha256)
    }

    @Test
    fun comparesVersionsWithoutDowngrading() {
        val release = ReleaseDescriptor("app", "App", "2.1.0", null, "url", null, "cert")
        assertTrue(isNewer(release, InstalledVersion("2.0.9", 100)))
        assertFalse(isNewer(release, InstalledVersion("2.1.0", 100)))
        assertFalse(isNewer(release, InstalledVersion("2.2.0", 100)))
    }

    @Test
    fun installsTheUpdaterLastSoItDoesNotKillTheBatch() {
        val releases = listOf(release("com.ivanmalison.wearupdater"), release("a.b"), release("c.d"))
        val ordered = installOrder(releases, "com.ivanmalison.wearupdater")
        assertEquals(listOf("a.b", "c.d", "com.ivanmalison.wearupdater"), ordered.map { it.packageName })
    }

    @Test
    fun backsOffBetweenRetriesWithoutGrowingForever() {
        assertEquals(1_000L, backoffMillis(1))
        assertEquals(2_000L, backoffMillis(2))
        assertEquals(8_000L, backoffMillis(8))
    }

    @Test
    fun describesErrorsThatCarryNoMessage() {
        assertEquals("software caused connection abort", describe(IOException("software caused connection abort")))
        assertEquals("IOException", describe(IOException()))
    }

    @Test
    fun formatsDownloadSizes() {
        assertEquals("18.6 MB", formatBytes(18_600_000))
        assertEquals("512 B", formatBytes(512))
        assertEquals("?", formatBytes(-1))
    }

    private fun release(packageName: String) =
        ReleaseDescriptor(packageName, packageName, "1.0.0", null, "url", null, "cert")
}
