package com.ks.streamflix.plugins.transcode

import com.ks.streamflix.api.AudioStream
import com.ks.streamflix.api.DeviceProfile
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.api.SubtitleStream
import com.ks.streamflix.api.VideoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionTest {
    private val tv = DeviceProfile(listOf("mp4", "mkv", "webm"), listOf("h264", "hevc"), listOf("aac", "ac3", "eac3"), maxHeight = 1080)
    private val chrome = DeviceProfile(listOf("mp4", "webm"), listOf("h264", "vp9", "av1"), listOf("aac", "mp3", "opus"))
    private fun info(v: String = "h264", h: Int = 1080, a: String = "aac", subs: List<SubtitleStream> = emptyList(), bitrate: Long? = 8_000_000) =
        MediaInfo(1, 60_000, null, bitrate, VideoStream(v, h * 16 / 9, h), listOf(AudioStream(1, a, 2, default = true)), subs)

    @Test fun directWhenEverythingFits() =
        assertEquals(PlayMethod.DIRECT, Decision.plan(info(), "mkv", tv).method)

    @Test fun remuxWhenOnlyTheContainerDoesNotFit() {
        val p = Decision.plan(info(), "mkv", chrome)
        assertEquals(PlayMethod.REMUX, p.method)
        assertEquals(listOf("container"), p.reasons)
        assertTrue(p.copyVideo && p.copyAudio)
    }

    @Test fun remuxTurnsUnplayableAudioIntoAac() {
        val p = Decision.plan(info(a = "dts"), "mkv", tv)
        assertEquals(PlayMethod.REMUX, p.method)
        assertTrue(p.copyVideo)
        assertFalse(p.copyAudio)
    }

    @Test fun transcodeForCodecHeightOrBitrate() {
        assertEquals(PlayMethod.TRANSCODE, Decision.plan(info(v = "hevc"), "mp4", chrome).method)
        val big = Decision.plan(info(h = 2160), "mkv", tv)
        assertEquals(PlayMethod.TRANSCODE, big.method)
        assertEquals(1080, big.scaleToHeight)
        assertEquals(8_000_000, big.videoBitrate)
        assertEquals(listOf("bitrate"), Decision.plan(info(bitrate = 200_000_000), "mkv", tv).reasons)
    }

    @Test fun pictureSubtitlesAreBurnedIn() {
        val pgs = SubtitleStream(3, "hdmv_pgs_subtitle", "eng")
        val p = Decision.plan(info(subs = listOf(pgs)), "mkv", tv, burnSubtitleIndex = 3)
        assertEquals(PlayMethod.TRANSCODE, p.method)
        assertEquals(3, p.burnSubtitleIndex)
        val args = Decision.ffmpegArgs(p, "in.mkv", 22, 88.0, "/tmp/s", "libx264")
        assertEquals(listOf("-ss", "88.000", "-i", "in.mkv"), args.take(4))
        assertTrue(args.containsAll(listOf("-output_ts_offset", "88.000", "-start_number", "22")))
        assertTrue(args.contains("[0:v:0][0:3]overlay,format=yuv420p[v]"))
        assertTrue(args.containsAll(listOf("-c:v", "libx264", "-hls_segment_type", "fmp4")))
        // A text track index is not burned in.
        assertEquals(PlayMethod.DIRECT, Decision.plan(info(subs = listOf(SubtitleStream(3, "subrip"))), "mkv", tv, burnSubtitleIndex = 3).method)
    }

    @Test fun remuxCopiesWithoutReencoding() {
        val args = Decision.ffmpegArgs(Decision.plan(info(), "mkv", chrome), "in.mkv", 0, 0.0, "/tmp/s", "h264_videotoolbox")
        assertTrue(args.containsAll(listOf("-c:v", "copy", "-c:a", "copy")))
        assertFalse("-ss" in args)
    }

    @Test fun segmentsAndThePlaylist() {
        // A transcode cuts every 4 s; the last segment is shorter.
        assertEquals(listOf(0.0, 4.0, 8.0), Decision.segmentStarts(10.0, null))
        // A remux cuts at the first keyframe at least 4 s after the last cut.
        assertEquals(listOf(0.0, 4.2, 9.0), Decision.segmentStarts(12.0, listOf(0.0, 2.0, 4.2, 6.0, 8.1, 9.0, 11.99)))
        val playlist = Decision.playlist(listOf(0.0, 4.0, 8.0), 10.0)
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD") && playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertEquals(listOf("4.000", "4.000", "2.000"), Regex("#EXTINF:([0-9.]+),").findAll(playlist).map { it.groupValues[1] }.toList())
        assertTrue(playlist.contains("seg_00002.m4s"))
    }
}
