package com.ks.streamflix.api

import kotlinx.serialization.SerialName
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
    /** Path of the WebVTT, relative to the server; empty for a picture track, which is burned in instead. */
    val path: String,
    /** A picture track (PGS, DVD): shown by transcoding with it burned into the video. */
    val burnIn: Boolean = false,
    /** The ffprobe index of an embedded track. */
    val streamIndex: Int? = null,
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

/** Plugin `transcode`: direct play, remux or transcode, decided from what the client can play. */
object TranscodeApi {
    const val BASE = "${ApiV1.BASE}/plugins/transcode"
    const val STATUS = "$BASE/status"
    fun playback(id: Long) = "$BASE/items/$id/playback"
    fun session(sessionId: String) = "$BASE/sessions/$sessionId"
}

/**
 * What a client can play. Containers are file extensions (`mp4`, `mkv`); codecs are ffprobe's
 * names (`h264`, `hevc`, `aac`, `eac3`).
 */
@Serializable
data class DeviceProfile(
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val maxHeight: Int = 2160,
    val maxBitrate: Long = 120_000_000,
)

@Serializable
data class PlaybackRequest(
    val profile: DeviceProfile,
    val startMs: Long = 0,
    /** An image subtitle stream (ffprobe index) to burn into the video; forces transcoding. */
    val burnSubtitleIndex: Int? = null,
)

@Serializable
enum class PlayMethod {
    @SerialName("direct") DIRECT,
    @SerialName("remux") REMUX,
    @SerialName("transcode") TRANSCODE,
}

@Serializable
data class PlaybackDecision(
    val method: PlayMethod,
    /** Path to play, relative to the server: the file itself for DIRECT, an HLS playlist otherwise. */
    val path: String,
    val sessionId: String? = null,
    /** Where the stream starts in the item (ms); positions in the HLS stream count from here. */
    val startMs: Long = 0,
    val durationMs: Long? = null,
    /** Why it was not direct play: `container`, `video`, `audio`, `height`, `bitrate`, `subtitles`. */
    val reasons: List<String> = emptyList(),
)

@Serializable
data class TranscodeStatus(val ffmpeg: Boolean, val encoder: String, val running: Int, val maxRunning: Int)

/** Plugin `users`: more users, the libraries each one sees, an age limit (owner only, except changing one's own password). */
object UsersApi {
    const val BASE = "${ApiV1.BASE}/plugins/users"
    const val ME_PASSWORD = "$BASE/me/password"
    fun user(id: Long) = "$BASE/$id"
}

@Serializable
data class UserSettings(
    val id: Long,
    val name: String,
    val owner: Boolean,
    /** The libraries this user sees; null for all. */
    val libraries: List<Long>? = null,
    /** The highest age certification this user sees (for example 12); null for no limit. */
    val maxAge: Int? = null,
    /** With an age limit: hide items that have no certification. */
    val blockUnrated: Boolean = false,
)

@Serializable
data class UserList(val users: List<UserSettings>)

@Serializable
data class NewUser(val name: String, val password: String, val libraries: List<Long>? = null, val maxAge: Int? = null, val blockUnrated: Boolean = false)

@Serializable
data class UserUpdate(val libraries: List<Long>? = null, val maxAge: Int? = null, val blockUnrated: Boolean = false, val password: String? = null)

@Serializable
data class PasswordChange(val current: String, val new: String)
