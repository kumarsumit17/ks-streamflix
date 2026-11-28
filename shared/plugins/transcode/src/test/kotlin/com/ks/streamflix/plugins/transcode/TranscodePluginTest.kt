package com.ks.streamflix.plugins.transcode

import com.ks.streamflix.api.ApiError
import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DeviceProfile
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.api.PlaybackDecision
import com.ks.streamflix.api.PlaybackRequest
import com.ks.streamflix.api.TranscodeApi
import com.ks.streamflix.api.TranscodeStatus
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.Measurement
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HLS sessions with the installed FFmpeg (skipped without it). */
class TranscodePluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)
    private val chrome = DeviceProfile(listOf("mp4", "webm"), listOf("h264"), listOf("aac"))

    @Test fun remuxTranscodeLimitsAndCleanUp() {
        val ff = Ffmpeg.locate()
        if (ff.ffmpeg == null || ff.ffprobe == null) return
        val media = Files.createTempDirectory("sf-media")
        val film = media.resolve("Film (2020).mkv")
        runBlocking {
            ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25", "-f", "lavfi", "-i", "sine=f=440", "-t", "12",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "50", "-c:a", "aac", "-shortest", "-y", film.toString()))
        }
        val plugin = TranscodePlugin(ff, maxRunning = 1)
        TestServer(plugins = listOf(plugin)).use { s ->
            s.setupOwner()
            val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val item = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.single()
            val status = s.send(TranscodeApi.STATUS).body().decode<TranscodeStatus>()
            println("transcode encoder: ${status.encoder}")

            fun play(profile: DeviceProfile, startMs: Long = 0) =
                s.send(TranscodeApi.playback(item.id), "POST", ApiV1.json.encodeToString(PlaybackRequest(profile, startMs)))

            // A Chrome-like client cannot open .mkv: remux, the streams copied.
            val started = System.nanoTime()
            val remux = play(chrome).body().decode<PlaybackDecision>()
            assertEquals(PlayMethod.REMUX, remux.method)
            assertEquals(listOf("container"), remux.reasons)
            // The whole playlist is there at once, as for a normal video.
            val playlist = s.send(remux.path)
            assertEquals(200, playlist.statusCode())
            assertEquals("application/vnd.apple.mpegurl", playlist.headers().firstValue("Content-Type").get())
            assertTrue(playlist.body().contains("#EXT-X-ENDLIST"))
            val segments = Regex("""seg_\d{5}\.m4s""").findAll(playlist.body()).map { it.value }.toList()
            assertTrue(segments.size >= 3, playlist.body())
            val base = remux.path.substringBeforeLast('/')
            assertEquals(200, s.bytes("$base/init.mp4").statusCode())
            assertEquals(200, s.bytes("$base/${segments.first()}").statusCode())
            val ms = (System.nanoTime() - started) / 1_000_000
            Measurement.record("transcode.firstSegment.remux", "$ms ms")
            // Seeking: the last segment, then back to the first, are both served.
            assertEquals(200, s.bytes("$base/${segments.last()}").statusCode())
            assertEquals(200, s.bytes("$base/${segments[1]}").statusCode())
            assertEquals(404, s.bytes("$base/seg_00999.m4s").statusCode())
            // Browsers without HLS: one fragmented MP4 of the init segment and every segment.
            val whole = s.bytes("$base/stream.mp4")
            assertEquals(200, whole.statusCode())
            assertEquals("video/mp4", whole.headers().firstValue("Content-Type").get())
            val wholeFile = Files.createTempFile("whole", ".mp4").also { Files.write(it, whole.body()) }
            val wholeInfo = runBlocking { ff.probe(1, wholeFile) }!!
            assertTrue(wholeInfo.durationMs!! in 11_000..13_000, "the whole film plays from it (${wholeInfo.durationMs} ms)")

            // One session at a time here: a second client is told the server is busy.
            val busy = play(chrome)
            assertEquals(429, busy.statusCode())
            assertEquals("transcode.error.busy", busy.body().decode<ApiError>().code)

            // Stopping removes the session and its files.
            assertEquals(204, s.send(TranscodeApi.session(remux.sessionId!!), "DELETE").statusCode())
            assertEquals(0, plugin.running)
            assertEquals(404, s.send(remux.path).statusCode())
            assertTrue(s.send(remux.path).body().contains("transcode.error.gone"))

            // A client without H.264: a transcode (here to H.264 for the test's sake), started at 4 s.
            val noH264 = DeviceProfile(listOf("mp4"), listOf("hevc"), listOf("aac"))
            val t0 = System.nanoTime()
            val transcode = play(noH264, startMs = 4_000).body().decode<PlaybackDecision>()
            assertEquals(PlayMethod.TRANSCODE, transcode.method)
            assertEquals(4_000, transcode.startMs)
            assertEquals(12_000L, transcode.durationMs!! / 1000 * 1000)
            val tBase = transcode.path.substringBeforeLast('/')
            // Starting at 4 s: the second segment comes first.
            assertEquals(200, s.bytes("$tBase/seg_00001.m4s").statusCode())
            val tMs = (System.nanoTime() - t0) / 1_000_000
            Measurement.record("transcode.firstSegment.transcode", "$tMs ms (${status.encoder})")

            // A file the client plays as it is comes back as direct play, with no session.
            val direct = play(DeviceProfile(listOf("mkv"), listOf("h264"), listOf("aac"))).body()
            assertTrue(direct.contains("\"direct\"") || plugin.running == 1, direct)
        }
        assertEquals(0, plugin.running, "stopping the server stops its sessions")
    }
}
