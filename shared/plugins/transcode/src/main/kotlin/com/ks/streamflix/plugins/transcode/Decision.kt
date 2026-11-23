package com.ks.streamflix.plugins.transcode

import com.ks.streamflix.api.DeviceProfile
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.ffmpeg.Ffmpeg

/** How to play an item on a client, and what FFmpeg has to do for it. */
data class Plan(
    val method: PlayMethod,
    val copyVideo: Boolean,
    val copyAudio: Boolean,
    /** The audio stream to use (ffprobe index), or null when there is none. */
    val audioIndex: Int?,
    /** A smaller height to scale to, or null to keep the size. */
    val scaleToHeight: Int?,
    val videoBitrate: Long,
    val burnSubtitleIndex: Int?,
    val reasons: List<String>,
)

object Decision {
    /** Video codecs that can be copied into fMP4 HLS segments. */
    private val hlsVideo = setOf("h264", "hevc", "av1", "vp9")
    /** Audio codecs that can be copied into fMP4 HLS segments. */
    private val hlsAudio = setOf("aac", "mp3", "ac3", "eac3", "opus", "flac")

    /** A sensible H.264 bitrate for a height, capped by the client's maximum. */
    fun bitrateFor(height: Int, max: Long): Long = minOf(
        max,
        when {
            height > 1440 -> 20_000_000L
            height > 1080 -> 12_000_000L
            height > 720 -> 8_000_000L
            height > 480 -> 4_000_000L
            else -> 2_000_000L
        },
    )

    /**
     * Direct play when the client plays the file as it is; remux (copy the streams into HLS) when it
     * plays the codecs but not the container; transcode (H.264/AAC) otherwise, or to burn in
     * picture subtitles. Audio that the client cannot play is turned into AAC in either case.
     */
    fun plan(info: MediaInfo, container: String, profile: DeviceProfile, burnSubtitleIndex: Int? = null): Plan {
        val reasons = mutableListOf<String>()
        val video = info.video
        val audio = info.audio.firstOrNull { it.default } ?: info.audio.firstOrNull()
        val videoCodecOk = video == null || video.codec in profile.videoCodecs
        val heightOk = video == null || video.height <= profile.maxHeight
        val bitrateOk = info.bitrate == null || info.bitrate!! <= profile.maxBitrate
        val audioOk = audio == null || audio.codec in profile.audioCodecs
        val containerOk = container.lowercase() in profile.containers
        val burn = burnSubtitleIndex?.takeIf { idx -> info.subtitles.any { it.index == idx && it.codec in Ffmpeg.IMAGE_SUBTITLES } }
        if (!containerOk) reasons += "container"
        if (!videoCodecOk) reasons += "video"
        if (!heightOk) reasons += "height"
        if (!bitrateOk) reasons += "bitrate"
        if (!audioOk) reasons += "audio"
        if (burn != null) reasons += "subtitles"
        val height = video?.height ?: 0
        val targetHeight = if (heightOk) null else profile.maxHeight
        val bitrate = bitrateFor(targetHeight ?: height, profile.maxBitrate)
        val copyAudio = audioOk && (audio == null || audio.codec in hlsAudio)
        return when {
            reasons.isEmpty() -> Plan(PlayMethod.DIRECT, true, true, audio?.index, null, 0, null, reasons)
            burn == null && videoCodecOk && heightOk && bitrateOk && (video == null || video.codec in hlsVideo) ->
                Plan(PlayMethod.REMUX, true, copyAudio, audio?.index, null, 0, null, reasons)
            else -> Plan(PlayMethod.TRANSCODE, false, copyAudio, audio?.index, targetHeight, bitrate, burn, reasons)
        }
    }

    /** HLS segment length (s). A transcode puts a keyframe at every multiple of it. */
    const val SEGMENT = 4.0

    /**
     * Segment start times (s) for an item of [duration] s. A transcode cuts every [SEGMENT] s; a
     * remux can only cut at the file's [keyframes], so it takes the first keyframe at least
     * [SEGMENT] s after the last cut, as FFmpeg's HLS muxer does.
     */
    fun segmentStarts(duration: Double, keyframes: List<Double>?): List<Double> {
        if (keyframes == null) return generateSequence(0.0) { it + SEGMENT }.takeWhile { it < duration - 0.05 }.toList().ifEmpty { listOf(0.0) }
        val starts = mutableListOf(0.0)
        keyframes.filter { it > 0 }.forEach { k -> if (k - starts.last() >= SEGMENT && k < duration - 0.05) starts += k }
        return starts
    }

    /** The whole HLS playlist (VOD), written before FFmpeg has made a single segment. */
    fun playlist(starts: List<Double>, duration: Double): String = buildString {
        val lengths = starts.indices.map { i -> (starts.getOrNull(i + 1) ?: duration) - starts[i] }
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:${kotlin.math.ceil(lengths.maxOrNull() ?: SEGMENT).toInt()}\n")
        append("#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n")
        lengths.forEachIndexed { i, len -> append("#EXTINF:%.3f,\n%s\n".format(java.util.Locale.ROOT, len, segmentName(i))) }
        append("#EXT-X-ENDLIST\n")
    }

    fun segmentName(index: Int) = "seg_%05d.m4s".format(index)

    /**
     * FFmpeg's arguments for an HLS session of [plan] from segment [startIndex] (which begins at
     * [startSeconds]), written into [dir]. Timestamps are shifted back to the item's own, so the
     * segments of every restart line up in one playlist.
     */
    fun ffmpegArgs(plan: Plan, input: String, startIndex: Int, startSeconds: Double, dir: String, encoder: String): List<String> = buildList {
        val start = "%.3f".format(java.util.Locale.ROOT, startSeconds)
        if (startSeconds > 0) addAll(listOf("-ss", start))
        addAll(listOf("-i", input))
        if (plan.burnSubtitleIndex != null) {
            val scale = plan.scaleToHeight?.let { ",scale=-2:$it" } ?: ""
            addAll(listOf("-filter_complex", "[0:v:0][0:${plan.burnSubtitleIndex}]overlay$scale,format=yuv420p[v]", "-map", "[v]"))
        } else {
            addAll(listOf("-map", "0:v:0?"))
        }
        plan.audioIndex?.let { addAll(listOf("-map", "0:$it")) }
        if (plan.copyVideo) {
            addAll(listOf("-c:v", "copy"))
        } else {
            if (plan.burnSubtitleIndex == null) {
                val scale = plan.scaleToHeight?.let { "scale=-2:$it," } ?: ""
                addAll(listOf("-vf", "${scale}format=yuv420p"))
            }
            addAll(listOf("-c:v", encoder))
            val rate = plan.videoBitrate
            when (encoder) {
                "libx264" -> addAll(listOf("-preset", "veryfast", "-crf", "22", "-maxrate", "$rate", "-bufsize", "${rate * 2}"))
                "h264_nvenc" -> addAll(listOf("-preset", "p4", "-b:v", "$rate", "-maxrate", "$rate", "-bufsize", "${rate * 2}"))
                else -> addAll(listOf("-b:v", "$rate", "-maxrate", "$rate"))
            }
            // A keyframe every 4 s, so each HLS segment starts with one.
            addAll(listOf("-g", "96", "-force_key_frames", "expr:gte(t,n_forced*${SEGMENT.toInt()})"))
        }
        if (plan.audioIndex != null) {
            if (plan.copyAudio) addAll(listOf("-c:a", "copy")) else addAll(listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k"))
        }
        addAll(listOf("-sn", "-dn", "-max_muxing_queue_size", "1024"))
        if (startSeconds > 0) addAll(listOf("-output_ts_offset", start))
        addAll(listOf(
            "-f", "hls", "-hls_time", "${SEGMENT.toInt()}", "-hls_playlist_type", "event", "-hls_segment_type", "fmp4",
            "-start_number", "$startIndex", "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", "$dir/seg_%05d.m4s", "$dir/ffmpeg.m3u8",
        ))
    }
}
