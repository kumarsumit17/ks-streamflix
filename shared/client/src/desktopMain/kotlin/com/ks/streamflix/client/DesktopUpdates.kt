package com.ks.streamflix.client

import com.ks.streamflix.client.plugin.OpenedOn
import com.ks.utility.appupdate.UpdatePlatform
import java.awt.Desktop
import java.io.File

/** macOS and Windows: the verified .dmg or .msi is opened; on Windows the app then closes so it can be replaced. */
class DesktopUpdateInstaller(private val onWindowsInstallerStarted: () -> Unit) : UpdateInstaller {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    override fun downloadDir() = File(System.getProperty("java.io.tmpdir"), "ks-streamflix-update")

    override fun open(file: File): OpenedOn {
        Desktop.getDesktop().open(file)
        return if (windows) OpenedOn.Windows.also { onWindowsInstallerStarted() } else OpenedOn.Mac
    }
}

/** The desktop app's updater, or null on a system without installers (Linux). */
fun desktopUpdater(settings: com.ks.streamflix.client.settings.SettingsStore, version: String, exit: () -> Unit): GitHubUpdater? {
    val platform = UpdatePlatform.current()?.takeIf { it != UpdatePlatform.ANDROID } ?: return null
    return GitHubUpdater(GitHubUpdater.REPO, version, platform, settings, DesktopUpdateInstaller(exit)).also { it.start() }
}
