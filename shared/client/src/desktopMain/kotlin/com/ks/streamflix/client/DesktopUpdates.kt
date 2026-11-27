package com.ks.streamflix.client

import com.ks.streamflix.client.plugin.OpenedOn
import com.ks.utility.appupdate.DesktopInstaller
import com.ks.utility.appupdate.UpdatePlatform
import java.io.File

/**
 * macOS and Windows: the verified .dmg or .msi goes to ks-utility's [DesktopInstaller], which marks
 * it as downloaded (Gatekeeper, SmartScreen), opens it and quits the app so it can be replaced
 * (at once on Windows, after a few seconds on macOS). [beforeQuit] stops the server first.
 */
class DesktopUpdateInstaller(beforeQuit: () -> Unit) : UpdateInstaller {
    private val installer = DesktopInstaller(appName = "KS StreamFlix", beforeQuit = beforeQuit)

    override fun downloadDir() = File(System.getProperty("java.io.tmpdir"), "ks-streamflix-update")

    override fun open(file: File): OpenedOn = when (installer.open(file)) {
        DesktopInstaller.Opened.MAC -> OpenedOn.Mac
        DesktopInstaller.Opened.WINDOWS -> OpenedOn.Windows
    }
}

/** The desktop app's updater, or null on a system without installers (Linux). [beforeQuit] stops the server. */
fun desktopUpdater(settings: com.ks.streamflix.client.settings.SettingsStore, version: String, beforeQuit: () -> Unit): GitHubUpdater? {
    val platform = UpdatePlatform.current()?.takeIf { it != UpdatePlatform.ANDROID } ?: return null
    return GitHubUpdater(GitHubUpdater.REPO, version, platform, settings, DesktopUpdateInstaller(beforeQuit)).also { it.start() }
}
