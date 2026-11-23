package com.ks.streamflix.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.settings.SettingsStore
import java.util.Locale

@Composable
actual fun ProvideLanguage(language: String, content: @Composable () -> Unit) {
    // Compose resources (1.12) read the JVM's default locale on Android too, so it is set for this
    // app's process (the system setting is not changed) and key() looks every text up again.
    // LocalConfiguration follows, for anything that reads the configuration.
    val base = LocalConfiguration.current
    val configuration = remember(base, language) {
        Locale.setDefault(Locale.forLanguageTag(language))
        Configuration(base).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    CompositionLocalProvider(LocalConfiguration provides configuration) { key(language) { content() } }
}

class AndroidSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("streamflix", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** KS JaiPlayer through its `ACTION_VIEW` hand-off (specs/cross-repo.md in KS-Suite). */
object AndroidJaiPlayer {
    const val PACKAGE = "com.ks.jaiplayer"

    private fun intent(url: String, title: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        setPackage(PACKAGE)
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True when JaiPlayer is installed and accepts a stream URL. */
    fun canOpen(context: Context): Boolean =
        context.packageManager.queryIntentActivities(intent("http://localhost/x", ""), 0).isNotEmpty()

    fun open(context: Context, url: String, title: String): Boolean = try {
        context.startActivity(intent(url, title))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The Android and Google TV app: the built-in Media3 player. */
class AndroidHost(private val context: Context, override val isTv: Boolean, override val appVersion: String) : PlatformHost {
    override val settings: SettingsStore = AndroidSettings(context)
    override val transport: HttpTransport = UrlConnectionTransport()
    override val preferredLanguages: List<String> = LocaleList.getDefault().toLanguageTags().split(',')
    override val hasPlayer = true

    override suspend fun discover() = discoverServers()

    @Composable
    override fun Player(request: PlayRequest, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) =
        Media3Player(request, isTv, onProgress, onExit)

    override fun openExternally(url: String): Boolean = AndroidJaiPlayer.open(context, url, "")
}
