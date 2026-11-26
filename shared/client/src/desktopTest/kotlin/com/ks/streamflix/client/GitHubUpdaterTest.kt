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
import kotlin.test.assertTrue

class GitHubUpdaterTest {
    private val sha = "a".repeat(64)
    private fun release(version: String) = """{"tag_name":"v$version","draft":false,"prerelease":false,"html_url":"https://github.com/kumasumit/ks-streamflix/releases/tag/v$version",
        "assets":[{"name":"ks-streamflix-v$version.apk","size":1900000,"browser_download_url":"https://github.com/kumasumit/ks-streamflix/releases/download/v$version/ks-streamflix-v$version.apk","digest":"sha256:$sha"},
                  {"name":"ks-streamflix-v$version-macos-arm64.dmg","size":76000000,"browser_download_url":"https://github.com/kumasumit/ks-streamflix/releases/download/v$version/ks-streamflix-v$version-macos-arm64.dmg","digest":"sha256:$sha"}]}"""

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

    @Test fun downloadsOnlyOverHttps() {
        assertFailsWith<IOException> { GitHubUpdater.download("http://example.invalid/x.apk", File.createTempFile("update", ".apk"), 10) }
    }
}
