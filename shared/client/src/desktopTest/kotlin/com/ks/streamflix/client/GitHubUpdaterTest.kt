package com.ks.streamflix.client

import com.ks.streamflix.client.plugin.OpenedOn
import com.ks.streamflix.client.plugin.UpdateStatus
import com.ks.streamflix.client.settings.MemorySettings
import com.ks.utility.appupdate.UpdatePlatform
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubUpdaterTest {
    private val sha = "a".repeat(64)
    /** One release as the GitHub API lists it; a platform release has only its platform's file. */
    private fun entry(version: String, tag: String = "v$version", mac: Boolean = true): String {
        val base = "https://github.com/kumasumit/ks-streamflix/releases/download/$tag"
        val apk = """{"name":"ks-streamflix-v$version.apk","size":1900000,"browser_download_url":"$base/ks-streamflix-v$version.apk","digest":"sha256:$sha"}"""
        val dmg = """{"name":"ks-streamflix-v$version-macos-arm64.dmg","size":76000000,"browser_download_url":"$base/ks-streamflix-v$version-macos-arm64.dmg","digest":"sha256:$sha"}"""
        return """{"tag_name":"$tag","draft":false,"prerelease":false,"html_url":"https://github.com/kumasumit/ks-streamflix/releases/tag/$tag",
            "assets":[${listOfNotNull(apk, dmg.takeIf { mac }).joinToString(",")}]}"""
    }

    /** The release list (newest first), as [GitHubRepo.releasesApiUrl] returns it. */
    private fun release(version: String) = "[${entry(version)}]"

    private val installer = object : UpdateInstaller {
        override fun downloadDir(): File = Files.createTempDirectory("upd").toFile()
        override fun open(file: File) = OpenedOn.Android
    }

    private fun updater(version: String, platform: UpdatePlatform, body: () -> String, settings: MemorySettings = MemorySettings()) =
        GitHubUpdater(GitHubUpdater.REPO, version, platform, settings, installer, fetch = { body() })

    @Test fun findsANewerVersionForThisPlatform() {
        val u = updater("1.0.0", UpdatePlatform.ANDROID, { release("1.1.0") })
        u.check(silent = false)
        assertEquals(UpdateStatus.Available("1.1.0", 1_900_000), u.status.value)
        val mac = updater("1.0.0", UpdatePlatform.MACOS_ARM64, { release("1.1.0") })
        mac.check(silent = false)
        assertEquals(UpdateStatus.Available("1.1.0", 76_000_000), mac.status.value)
    }

    @Test fun aPlatformReleaseIsOnlyForItsPlatform() {
        // android-v1.3.0 is newest but has only the APK; v1.2.0 (every platform) comes before it.
        val list = "[${entry("1.3.0", tag = "android-v1.3.0", mac = false)},${entry("1.2.0")}]"
        val phone = updater("1.2.0", UpdatePlatform.ANDROID, { list })
        phone.check(silent = false)
        assertEquals(UpdateStatus.Available("1.3.0", 1_900_000), phone.status.value)
        val mac = updater("1.1.0", UpdatePlatform.MACOS_ARM64, { list })
        mac.check(silent = false)
        assertEquals(UpdateStatus.Available("1.2.0", 76_000_000), mac.status.value, "the Mac skips the Android release")
    }

    @Test fun upToDateAndFailures() {
        val same = updater("1.1.0", UpdatePlatform.ANDROID, { release("1.1.0") })
        same.check(silent = false)
        assertEquals(UpdateStatus.UpToDate("1.1.0"), same.status.value)
        val offline = updater("1.0.0", UpdatePlatform.ANDROID, { throw IOException("offline") })
        offline.check(silent = true)
        assertEquals(UpdateStatus.Idle, offline.status.value, "the automatic check never shows an error")
        offline.check(silent = false)
        assertTrue(offline.status.value is UpdateStatus.Failed)
    }

    @Test fun aSourceBuildAndTheSwitch() {
        val settings = MemorySettings()
        val dev = updater("0.0.0", UpdatePlatform.ANDROID, { release("1.1.0") }, settings)
        assertTrue(dev.isDevBuild)
        dev.setAutoCheck(false)
        assertFalse(updater("1.0.0", UpdatePlatform.ANDROID, { "" }, settings).autoCheck.value, "the switch is remembered")
    }

    @Test fun aDevBuildIsNeverOfferedARelease() {
        val u = updater("1.4.0-dev+abc1234", UpdatePlatform.MACOS_ARM64, { release("9.0.0") })
        assertTrue(u.isDevBuild)
        u.check(silent = false)
        assertIs<UpdateStatus.UpToDate>(u.status.value)
    }

    @Test fun downloadsOnlyOverHttps() {
        assertFailsWith<IOException> { GitHubUpdater.download("http://example.invalid/x.apk", File.createTempFile("update", ".apk"), 10) }
    }
}
