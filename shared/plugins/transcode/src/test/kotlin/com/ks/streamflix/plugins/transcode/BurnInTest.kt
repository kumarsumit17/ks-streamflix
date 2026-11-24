package com.ks.streamflix.plugins.transcode

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DeviceProfile
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.api.PlaybackDecision
import com.ks.streamflix.api.PlaybackRequest
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Burning in a picture subtitle, with a real one: FFmpeg cannot turn text into Blu-ray (PGS)
 * subtitles, so this test writes a small PGS file itself (a white box from 0.5 s to 3 s), muxes it
 * into a black film, and checks that a frame of the transcoded HLS stream shows the box.
 */
class BurnInTest {
    /** One PGS display set: a white [w]×[h] box at ([x], [y]) on a [width]×[height] screen from [startMs], cleared at [endMs]. */
    private fun pgs(width: Int, height: Int, x: Int, y: Int, w: Int, h: Int, startMs: Long, endMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun segment(ptsMs: Long, type: Int, data: ByteArray) {
            val pts = ptsMs * 90
            out.write(byteArrayOf(0x50, 0x47))
            out.write(byteArrayOf((pts shr 24).toByte(), (pts shr 16).toByte(), (pts shr 8).toByte(), pts.toByte()))
            out.write(ByteArray(4)) // DTS
            out.write(type)
            out.write(u16(data.size))
            out.write(data)
        }
        fun pcs(objects: Boolean, number: Int) = u16(width) + u16(height) + byteArrayOf(0x10) + u16(number) +
            byteArrayOf(if (objects) 0x80.toByte() else 0, 0, 0, if (objects) 1 else 0) +
            (if (objects) u16(0) + byteArrayOf(0, 0) + u16(x) + u16(y) else ByteArray(0))
        val wds = byteArrayOf(1, 0) + u16(x) + u16(y) + u16(w) + u16(h)
        // Palette: entry 1 is opaque white (Y, Cr, Cb, alpha).
        val pds = byteArrayOf(0, 0, 1, 235.toByte(), 128.toByte(), 128.toByte(), 255.toByte())
        // Run-length lines: w pixels of colour 1, then end of line.
        val line = byteArrayOf(0, (0xC0 or (w shr 8)).toByte(), w.toByte(), 1, 0, 0)
        val rle = ByteArray(line.size * h).also { for (i in 0 until h) line.copyInto(it, i * line.size) }
        val length = rle.size + 4
        val ods = u16(0) + byteArrayOf(0, 0xC0.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()) + u16(w) + u16(h) + rle
        segment(startMs, 0x16, pcs(true, 0)); segment(startMs, 0x17, wds); segment(startMs, 0x14, pds); segment(startMs, 0x15, ods); segment(startMs, 0x80, ByteArray(0))
        segment(endMs, 0x16, pcs(false, 1)); segment(endMs, 0x17, wds); segment(endMs, 0x80, ByteArray(0))
        return out.toByteArray()
    }

    /** The grey value of pixel ([px], [py]) in the frame at [atSeconds] of [video]. */
    private fun pixel(ff: Ffmpeg, video: Path, atSeconds: Double, width: Int, px: Int, py: Int): Int {
        val raw = runBlocking { ff.ffmpegOutput(listOf("-ss", "$atSeconds", "-i", video.toString(), "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-")) }!!
        return raw[py * width + px].toInt() and 0xff
    }

    @Test fun aPictureSubtitleIsBurnedIntoTheStream() {
        val ff = Ffmpeg.locate()
        if (ff.ffmpeg == null || ff.ffprobe == null) return
        val dir = Files.createTempDirectory("burn")
        val sup = dir.resolve("subs.sup").also { Files.write(it, pgs(640, 360, 220, 280, 200, 50, 500, 3000)) }
        val media = Files.createDirectories(dir.resolve("media"))
        val film = media.resolve("Boxed (2024).mkv")
        runBlocking {
            ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "color=c=black:s=640x360:r=25", "-i", sup.toString(), "-t", "4",
                "-map", "0:v", "-map", "1:s", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:s", "copy", "-y", film.toString()))
        }
        val info = runBlocking { ff.probe(1, film) }!!
        val pgsTrack = info.subtitles.single()
        assertEquals("hdmv_pgs_subtitle", pgsTrack.codec)

        TestServer(plugins = listOf(TranscodePlugin(ff))).use { s ->
            s.setupOwner()
            val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val item = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${lib.id}").body()).items.single()
            // A client that plays the file as it is: only the burn-in makes it a transcode.
            val profile = DeviceProfile(listOf("mkv"), listOf("h264"), listOf("aac"))
            val decision = ApiV1.json.decodeFromString<PlaybackDecision>(
                s.send(TranscodeApi.playback(item.id), "POST", ApiV1.json.encodeToString(PlaybackRequest(profile, 0, pgsTrack.index))).body(),
            )
            assertEquals(PlayMethod.TRANSCODE, decision.method)
            assertEquals(listOf("subtitles"), decision.reasons)
            val base = decision.path.substringBeforeLast('/')
            val stream = dir.resolve("out.mp4").toFile()
            stream.writeBytes(s.bytes("$base/init.mp4").body() + s.bytes("$base/seg_00000.m4s").body())
            // Inside the box at 1 s: white. Outside it: still black.
            val inside = pixel(ff, stream.toPath(), 1.0, 640, 320, 305)
            val outside = pixel(ff, stream.toPath(), 1.0, 640, 50, 50)
            assertTrue(inside > 200, "the subtitle is in the picture (grey $inside)")
            assertTrue(outside < 40, "the rest stays black (grey $outside)")
        }
    }
}

private typealias TranscodeApi = com.ks.streamflix.api.TranscodeApi
