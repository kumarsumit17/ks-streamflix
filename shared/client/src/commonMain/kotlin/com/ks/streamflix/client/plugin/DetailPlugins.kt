package com.ks.streamflix.client.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemDetails
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.SubtitleTrack
import com.ks.streamflix.client.PlaySubtitle
import com.ks.streamflix.client.net.StreamflixClient
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.client_media_audio
import com.ks.streamflix.client.resources.client_media_subtitles
import com.ks.streamflix.client.resources.client_subtitles_heading
import com.ks.streamflix.client.resources.client_subtitles_off
import com.ks.streamflix.client.resources.details_attribution_tmdb
import com.ks.streamflix.client.ui.formatTime
import com.ks.streamflix.client.ui.ChoiceButton
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Decoded images, kept to a fixed number so a long library does not fill a low-memory TV.
 * Images are fetched at the size they are shown (the server resizes them).
 */
object ImageCache {
    private const val MAX = 60
    private val map = LinkedHashMap<String, ImageBitmap?>(MAX, 0.75f, true)

    suspend fun get(key: String, load: suspend () -> ImageBitmap?): ImageBitmap? {
        synchronized(map) { if (map.containsKey(key)) return map[key] }
        val image = load()
        synchronized(map) {
            map[key] = image
            while (map.size > MAX) map.remove(map.keys.first())
        }
        return image
    }
}

/**
 * Plugin `metadata` (pairs with the server's metadata plugins, `local-metadata` and `tmdb`):
 * posters on the cards, and the plot, genres and rating on an item's page, with TMDB's notice
 * when TMDB supplied anything.
 */
class MetadataPlugin : ClientPlugin {
    override val id = "metadata"

    override fun install(context: ClientPluginContext) {
        context.addCardImage { client, item, width ->
            ImageCache.get("${client.server}|${item.id}|poster|$width") {
                client.image(item.id, ImageKind.POSTER, width)?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
            }
        }
        context.addDetailSection(object : DetailSection {
            var details by mutableStateOf<ItemDetails?>(null)

            override suspend fun load(client: StreamflixClient, item: Item) {
                details = null
                details = runCatching { client.details(item.id) }.getOrNull()
            }

            @Composable
            override fun Content(item: Item) {
                val d = details?.takeIf { it.itemId == item.id } ?: return
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val facts = listOfNotNull(d.rating?.let { "★ ${(it * 10).toInt() / 10.0}" }, d.genres.takeIf { it.isNotEmpty() }?.joinToString(", "))
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if ("tmdb" in d.sources) {
                        Text(stringResource(Res.string.details_attribution_tmdb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        })
    }
}

/** Plugin `media-info`: one line on an item's page, such as `1:42:10 · 1920×1080 · H264 · 2 audio tracks`. */
class MediaInfoPlugin : ClientPlugin {
    override val id = "media-info"

    override fun install(context: ClientPluginContext) {
        context.addDetailSection(object : DetailSection {
            var info by mutableStateOf<MediaInfo?>(null)

            override suspend fun load(client: StreamflixClient, item: Item) {
                info = null
                info = runCatching { client.mediaInfo(item.id) }.getOrNull()
            }

            @Composable
            override fun Content(item: Item) {
                val m = info?.takeIf { it.itemId == item.id } ?: return
                val parts = buildList {
                    m.durationMs?.let { add(formatTime(it)) }
                    m.video?.let { add("${it.width}×${it.height}"); add(it.codec.uppercase() + if (it.hdr) " HDR" else "") }
                    if (m.audio.isNotEmpty()) add(pluralStringResource(Res.plurals.client_media_audio, m.audio.size, m.audio.size.toString()))
                    if (m.subtitles.isNotEmpty()) add(pluralStringResource(Res.plurals.client_media_subtitles, m.subtitles.size, m.subtitles.size.toString()))
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }
}

/**
 * Plugin `subtitles` (pairs with the server plugin): a subtitle choice on an item's page (Off or
 * a track; the default track is chosen first), which the built-in player then shows.
 */
class SubtitlesPlugin : ClientPlugin {
    override val id = "subtitles"

    override fun install(context: ClientPluginContext) {
        var forItem by mutableStateOf<Long?>(null)
        var tracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
        var chosen by mutableStateOf<String?>(null)

        context.addDetailSection(object : DetailSection {
            override suspend fun load(client: StreamflixClient, item: Item) {
                forItem = item.id
                tracks = emptyList()
                tracks = runCatching { client.subtitles(item.id) }.getOrDefault(emptyList())
                chosen = (tracks.firstOrNull { it.default } ?: tracks.firstOrNull { it.forced })?.id
            }

            @Composable
            override fun Content(item: Item) {
                if (forItem != item.id || tracks.isEmpty()) return
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.client_subtitles_heading), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val choices = listOf<Pair<String?, String>>(null to stringResource(Res.string.client_subtitles_off)) + tracks.map { it.id to it.label }
                        choices.forEach { (id, label) -> ChoiceButton(label, chosen == id) { chosen = id } }
                    }
                }
            }
        })
        context.addPlayDecorator { client, item, request ->
            val track = tracks.takeIf { forItem == item.id }?.firstOrNull { it.id == chosen } ?: return@addPlayDecorator request
            // A picture track is burned in by the server's transcode plugin; a text track is drawn by the player.
            if (track.burnIn) request.copy(burnSubtitleIndex = track.streamIndex)
            else request.copy(subtitle = PlaySubtitle(client.url(track.path), track.language, track.label))
        }
    }
}
