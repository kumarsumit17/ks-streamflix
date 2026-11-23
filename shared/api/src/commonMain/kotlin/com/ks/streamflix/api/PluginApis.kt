package com.ks.streamflix.api

import kotlinx.serialization.Serializable

/** Plugin `watch-state`: progress, watched, continue watching (per user). */
object WatchStateApi {
    const val BASE = "${ApiV1.BASE}/plugins/watch-state"
    const val CONTINUE = "$BASE/continue"
    fun progress(libraryId: Long) = "$BASE/progress?library=$libraryId"
    fun item(id: Long) = "$BASE/items/$id"
    fun watched(id: Long) = "$BASE/items/$id/watched"

    /** At or past this share of the duration an item counts as watched. */
    const val WATCHED_SHARE = 0.9
}

@Serializable
data class WatchProgress(val itemId: Long, val positionMs: Long, val durationMs: Long, val watched: Boolean, val updatedAt: Long)

@Serializable
data class ProgressUpdate(val positionMs: Long, val durationMs: Long)

@Serializable
data class WatchedUpdate(val watched: Boolean)

@Serializable
data class ProgressList(val progress: List<WatchProgress>)

@Serializable
data class ContinueItem(val item: Item, val progress: WatchProgress)

@Serializable
data class ContinueList(val items: List<ContinueItem>)

/** Plugin `discovery`: a client sends [QUESTION] as a UDP broadcast to [PORT]; servers answer with a [DiscoveryAnswer] in JSON. */
object DiscoveryApi {
    const val PORT = 8742
    const val QUESTION = "KS-STREAMFLIX-WHO-IS-THERE/1"
}

@Serializable
data class DiscoveryAnswer(val name: String, val port: Int, val version: String, val apiVersion: Int = ApiV1.VERSION)

/** Plugin `media-info`: what ffprobe reads from a file. */
object MediaInfoApi {
    const val BASE = "${ApiV1.BASE}/plugins/media-info"
    const val STATUS = "$BASE/status"
    fun item(id: Long) = "$BASE/items/$id"
}

@Serializable
data class MediaInfo(
    val itemId: Long,
    val durationMs: Long? = null,
    val container: String? = null,
    val bitrate: Long? = null,
    val video: VideoStream? = null,
    val audio: List<AudioStream> = emptyList(),
    val subtitles: List<SubtitleStream> = emptyList(),
)

@Serializable
data class VideoStream(val codec: String, val width: Int, val height: Int, val frameRate: Double? = null, val hdr: Boolean = false)

@Serializable
data class AudioStream(val index: Int, val codec: String, val channels: Int? = null, val language: String? = null, val title: String? = null, val default: Boolean = false)

@Serializable
data class SubtitleStream(val index: Int, val codec: String, val language: String? = null, val title: String? = null, val forced: Boolean = false, val default: Boolean = false)

/** Whether the server found FFmpeg; without it media details and embedded subtitles are off. */
@Serializable
data class ToolStatus(val ffprobe: Boolean, val ffmpeg: Boolean, val version: String? = null)

/** Plugin `subtitles`: side-car files and embedded text tracks, served as WebVTT. */
object SubtitlesApi {
    const val BASE = "${ApiV1.BASE}/plugins/subtitles"
    fun list(id: Long) = "$BASE/items/$id"
    fun track(id: Long, trackId: String) = "$BASE/items/$id/tracks/$trackId.vtt"
}

@Serializable
data class SubtitleTrack(
    /** Stable within the item: `file-<n>` for a side-car file, `stream-<index>` for an embedded track. */
    val id: String,
    val language: String? = null,
    val label: String,
    val forced: Boolean = false,
    val default: Boolean = false,
    /** Path of the WebVTT, relative to the server. */
    val path: String,
)

@Serializable
data class SubtitleList(val tracks: List<SubtitleTrack>)

/** Plugin `tmdb`: settings (owner only). The key is never sent back. */
object TmdbApi {
    const val BASE = "${ApiV1.BASE}/plugins/tmdb"
    const val SETTINGS = "$BASE/settings"
}

@Serializable
data class TmdbSettings(val enabled: Boolean, val hasKey: Boolean, val language: String = "en-US")

@Serializable
data class TmdbSettingsUpdate(val enabled: Boolean, val apiKey: String? = null, val language: String? = null)
