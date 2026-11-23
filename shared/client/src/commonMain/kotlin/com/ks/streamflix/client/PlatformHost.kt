package com.ks.streamflix.client

import androidx.compose.runtime.Composable
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.settings.SettingsStore

/** A server found on the home network. */
data class FoundServer(val name: String, val address: String)

/** What the built-in player is asked to play. */
class PlayRequest(val url: String, val headers: Map<String, String>, val title: String, val startMs: Long)

/** What each platform gives the shared app. */
interface PlatformHost {
    /** A TV: D-pad layout, overscan margins. */
    val isTv: Boolean
    val settings: SettingsStore
    val transport: HttpTransport
    /** The system's preferred languages (BCP 47), for "Same as the system". */
    val preferredLanguages: List<String>
    val appVersion: String

    /** Servers answering on the home network within a moment. */
    suspend fun discover(): List<FoundServer>

    /** True when the app has its own player screen (Android); otherwise [openExternally] plays. */
    val hasPlayer: Boolean

    /** The full-screen player. Calls [onProgress] about every 10 s and [onExit] with the last position. */
    @Composable
    fun Player(request: PlayRequest, onProgress: (positionMs: Long, durationMs: Long) -> Unit, onExit: (positionMs: Long, durationMs: Long) -> Unit)

    /** Opens a stream link in another player (desktop: KS JaiPlayer if installed, else the browser). */
    fun openExternally(url: String): Boolean
}

/** Applies a language to the Compose resources below it. */
@Composable
expect fun ProvideLanguage(language: String, content: @Composable () -> Unit)
