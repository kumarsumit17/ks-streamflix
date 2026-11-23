package com.ks.streamflix.client.plugin

import androidx.compose.runtime.Composable
import com.ks.streamflix.api.Item
import com.ks.streamflix.client.PlatformHost
import com.ks.streamflix.client.net.StreamflixClient

/**
 * A feature outside the apps' core (sign-in, browsing, playing). Each entry point passes the
 * plugins it includes; the core never knows which exist (specs/media-server-plan.md).
 */
interface ClientPlugin {
    val id: String
    fun install(context: ClientPluginContext)
}

/** A row on the home screen above the libraries. [refresh] is called whenever the home screen opens. */
interface HomeSection {
    suspend fun refresh(client: StreamflixClient)

    @Composable
    fun Content(open: (Item) -> Unit)
}

/** A button on an item's page, after Play. */
class ItemAction(
    val id: String,
    val label: @Composable () -> String,
    val isAvailable: () -> Boolean = { true },
    val run: suspend (client: StreamflixClient, item: Item) -> Unit,
)

/** Hears what the built-in player does. */
interface PlaybackListener {
    suspend fun progress(client: StreamflixClient, item: Item, positionMs: Long, durationMs: Long) {}
    suspend fun stopped(client: StreamflixClient, item: Item, positionMs: Long, durationMs: Long) {}
}

class ClientPluginContext internal constructor(val host: PlatformHost) {
    internal val homeSections = mutableListOf<HomeSection>()
    internal val itemActions = mutableListOf<ItemAction>()
    internal val playbackListeners = mutableListOf<PlaybackListener>()
    internal val resumeProviders = mutableListOf<suspend (StreamflixClient, Item) -> Long?>()

    fun addHomeSection(section: HomeSection) { homeSections += section }
    fun addItemAction(action: ItemAction) { itemActions += action }
    fun addPlaybackListener(listener: PlaybackListener) { playbackListeners += listener }

    /** Where to start an item (ms), for "Resume"; the first non-null answer wins. */
    fun addResumePosition(provider: suspend (StreamflixClient, Item) -> Long?) { resumeProviders += provider }
}

/** What the installed plugins added, for the core's screens. */
class InstalledPlugins(val homeSections: List<HomeSection>, val itemActions: List<ItemAction>,
                       val playbackListeners: List<PlaybackListener>, val resumeProviders: List<suspend (StreamflixClient, Item) -> Long?>) {
    companion object {
        fun install(plugins: List<ClientPlugin>, host: PlatformHost): InstalledPlugins {
            val ids = plugins.map { it.id }
            require(ids.size == ids.toSet().size) { "Two plugins have the same id: $ids" }
            val context = ClientPluginContext(host)
            plugins.forEach { it.install(context) }
            return InstalledPlugins(context.homeSections, context.itemActions, context.playbackListeners, context.resumeProviders)
        }
    }
}
