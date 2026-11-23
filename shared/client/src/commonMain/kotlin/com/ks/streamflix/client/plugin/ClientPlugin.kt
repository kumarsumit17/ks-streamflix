package com.ks.streamflix.client.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.ks.streamflix.client.PlayRequest
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

/** A part of an item's page under its title (details, tracks, a subtitle choice). [load] runs when the page opens. */
interface DetailSection {
    suspend fun load(client: StreamflixClient, item: Item)

    @Composable
    fun Content(item: Item)
}

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
    internal val cardImages = mutableListOf<suspend (StreamflixClient, Item, Int) -> ImageBitmap?>()
    internal val detailSections = mutableListOf<DetailSection>()
    internal val playDecorators = mutableListOf<suspend (StreamflixClient, Item, PlayRequest) -> PlayRequest>()

    fun addHomeSection(section: HomeSection) { homeSections += section }
    fun addItemAction(action: ItemAction) { itemActions += action }
    fun addPlaybackListener(listener: PlaybackListener) { playbackListeners += listener }

    /** Where to start an item (ms), for "Resume"; the first non-null answer wins. */
    fun addResumePosition(provider: suspend (StreamflixClient, Item) -> Long?) { resumeProviders += provider }

    /** The picture on an item's card, [width] pixels wide; the first non-null answer wins. */
    fun addCardImage(provider: suspend (client: StreamflixClient, item: Item, width: Int) -> ImageBitmap?) { cardImages += provider }

    fun addDetailSection(section: DetailSection) { detailSections += section }

    /** Changes what the built-in player is asked to play (for example adds the chosen subtitles). */
    fun addPlayDecorator(decorator: suspend (StreamflixClient, Item, PlayRequest) -> PlayRequest) { playDecorators += decorator }
}

/** What the installed plugins added, for the core's screens. */
class InstalledPlugins(
    val homeSections: List<HomeSection>,
    val itemActions: List<ItemAction>,
    val playbackListeners: List<PlaybackListener>,
    val resumeProviders: List<suspend (StreamflixClient, Item) -> Long?>,
    val cardImages: List<suspend (StreamflixClient, Item, Int) -> ImageBitmap?> = emptyList(),
    val detailSections: List<DetailSection> = emptyList(),
    val playDecorators: List<suspend (StreamflixClient, Item, PlayRequest) -> PlayRequest> = emptyList(),
) {
    companion object {
        fun install(plugins: List<ClientPlugin>, host: PlatformHost): InstalledPlugins {
            val ids = plugins.map { it.id }
            require(ids.size == ids.toSet().size) { "Two plugins have the same id: $ids" }
            val context = ClientPluginContext(host)
            plugins.forEach { it.install(context) }
            return InstalledPlugins(
                context.homeSections, context.itemActions, context.playbackListeners, context.resumeProviders,
                context.cardImages, context.detailSections, context.playDecorators,
            )
        }
    }
}
