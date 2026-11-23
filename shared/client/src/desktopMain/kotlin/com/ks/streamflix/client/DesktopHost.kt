package com.ks.streamflix.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.settings.SettingsStore
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.prefs.Preferences

@Composable
actual fun ProvideLanguage(language: String, content: @Composable () -> Unit) {
    // Compose resources follow the JVM's default locale on desktop; key() rebuilds the subtree
    // so every text is looked up again in the new language.
    remember(language) { Locale.setDefault(Locale.forLanguageTag(language)) }
    key(language) { content() }
}

/** Settings in java.util.prefs (the macOS defaults database, the Windows registry). */
class PrefsSettings(node: String = "com/ks/streamflix/client") : SettingsStore {
    private val prefs = Preferences.userRoot().node(node)
    override fun getString(key: String): String? = prefs.get(key, null)
    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }
}

/** KS JaiPlayer on this computer, if installed in the usual place. */
object DesktopJaiPlayer {
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private val macApp = File("/Applications/KS JaiPlayer.app")
    private val windowsExe: File? = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })
        .map { File(it, "KS JaiPlayer\\KS JaiPlayer.exe") }.firstOrNull { it.isFile }

    fun isInstalled(): Boolean = if (isMac) macApp.isDirectory else windowsExe != null

    fun open(url: String): Boolean = runCatching {
        if (isMac) ProcessBuilder("open", "-a", macApp.path, url).start()
        else ProcessBuilder(windowsExe!!.path, url).start()
        true
    }.getOrDefault(false)
}

/** The desktop app: no built-in player; videos open in KS JaiPlayer, else the browser. */
class DesktopHost(override val appVersion: String, override val settings: SettingsStore = PrefsSettings()) : PlatformHost {
    override val isTv = false
    override val transport: HttpTransport = UrlConnectionTransport()
    override val preferredLanguages: List<String> = listOf(Locale.getDefault().toLanguageTag())
    override val hasPlayer = false

    override suspend fun discover() = discoverServers()

    @Composable
    override fun Player(request: PlayRequest, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) = Unit

    override fun openExternally(url: String): Boolean =
        DesktopJaiPlayer.isInstalled() && DesktopJaiPlayer.open(url) ||
            runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
}
