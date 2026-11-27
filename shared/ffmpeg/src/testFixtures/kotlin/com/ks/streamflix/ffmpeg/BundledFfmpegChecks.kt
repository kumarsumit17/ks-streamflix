package com.ks.streamflix.ffmpeg

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks an FFmpeg build bundled in a desktop app (gradle/bundled-ffmpeg.gradle.kts) does what the
 * server needs, with nothing but itself: no GPL parts, a working H.264 encoder that is not libx264,
 * probing, keyframes, HLS in fragmented MP4 with AAC (as the transcode plugin makes it), and text
 * subtitles to WebVTT. The desktop modules' tests run it on the copy they bundle.
 */
object BundledFfmpegChecks {
    fun run(dir: File, windows: Boolean) {
        val exe = if (windows) ".exe" else ""
        val ff = Ffmpeg.locate(env = mapOf("PATH" to ""), windows = windows, bundled = dir.path)
        assertEquals(File(dir, "ffmpeg$exe"), ff.ffmpeg, "the bundled ffmpeg is found")
        assertEquals(File(dir, "ffprobe$exe"), ff.ffprobe, "the bundled ffprobe is found")
        assertTrue(File(dir, "LICENSE.txt").isFile && File(dir, "SOURCE.txt").isFile, "the licence and the source note travel with it")

        val config = output(listOf(ff.ffmpeg!!.path, "-hide_banner", "-buildconf"))
        assertFalse("--enable-gpl" in config || "--enable-nonfree" in config || "libx264" in config, "LGPL only:\n$config")

        val encoder = ff.h264Encoder
        if (encoder == "libx264") {
            // Say why: which H.264 encoders the build lists, and what each one answers.
            val listed = output(listOf(ff.ffmpeg.path, "-hide_banner", "-encoders")).lines().filter { "h264" in it }
            val tries = listed.mapNotNull { Regex("""\s(h264_\w+)\s""").find(it)?.groupValues?.get(1) }.joinToString("\n") { enc ->
                val p = ProcessBuilder(ff.ffmpeg.path, "-hide_banner", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "256x144", "-r", "25",
                    "-i", "-", "-frames:v", "5", "-c:v", enc, "-f", "null", "-").redirectErrorStream(true).start()
                runCatching { p.outputStream.use { o -> repeat(5) { o.write(ByteArray(256 * 144 * 3 / 2)) } } }
                "$enc: " + p.inputStream.readAllBytes().decodeToString().trim().take(400)
            }
            println("No H.264 encoder works here.\nListed:\n${listed.joinToString("\n")}\nTried:\n$tries")
        }
        assertNotEquals("libx264", encoder, "a working H.264 encoder of the bundle (see the output above)")

        val work = Files.createTempDirectory("bundled-ffmpeg").toFile()
        // Two seconds of video (raw grey frames on stdin) and of silence, encoded as the server would.
        val pcm = File(work, "silence.pcm").apply { writeBytes(ByteArray(48_000 * 2 * 2 * 2)) }
        val clip = File(work, "clip.mp4")
        val encode = ProcessBuilder(
            ff.ffmpeg.path, "-hide_banner", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "320x240", "-r", "25", "-i", "-",
            "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", pcm.path, "-c:v", encoder, "-c:a", "aac", "-shortest", clip.path,
        ).redirectErrorStream(true).start()
        encode.outputStream.use { out -> repeat(50) { i -> out.write(ByteArray(320 * 240 * 3 / 2) { (i * 4).toByte() }) } }
        val encodeLog = encode.inputStream.readAllBytes().decodeToString()
        assertTrue(encode.waitFor(60, TimeUnit.SECONDS) && encode.exitValue() == 0, "encoding failed: $encodeLog")

        val info = runBlocking { ff.probe(1, clip.toPath()) }
        assertNotNull(info, "ffprobe reads the clip")
        assertEquals("h264", info.video?.codec)
        assertEquals("aac", info.audio.firstOrNull()?.codec)
        assertTrue(runBlocking { ff.keyframes(clip.toPath()) }.orEmpty().isNotEmpty(), "keyframes are listed")

        // HLS with fragmented MP4, as the transcode plugin asks for it.
        val hls = File(work, "hls").apply { mkdirs() }
        val made = runBlocking {
            ff.ffmpegOutput(listOf("-i", clip.path, "-map", "0:v:0", "-map", "0:a:0", "-c:v", encoder, "-c:a", "aac", "-ac", "2",
                "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "event", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", Ffmpeg.path(File(hls, "seg_%05d.m4s").path), Ffmpeg.path(File(hls, "index.m3u8").path)))
        }
        assertNotNull(made, "HLS failed")
        assertTrue(File(hls, "init.mp4").length() > 0 && (hls.listFiles()?.count { it.name.endsWith(".m4s") } ?: 0) >= 1,
            "init segment and media segments; found: ${hls.listFiles()?.joinToString { "${it.name} (${it.length()})" }}")

        // A text subtitle to WebVTT, as the subtitles plugin serves it.
        val srt = File(work, "sub.srt").apply { writeText("1\n00:00:00,500 --> 00:00:01,500\nNamaste\n") }
        val vtt = runBlocking { ff.ffmpegOutput(listOf("-i", srt.path, "-f", "webvtt", "-")) }?.decodeToString().orEmpty()
        assertTrue(vtt.startsWith("WEBVTT") && "Namaste" in vtt, "WebVTT: $vtt")
    }

    private fun output(command: List<String>): String {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = p.inputStream.readAllBytes().decodeToString()
        p.waitFor(30, TimeUnit.SECONDS)
        return text
    }
}
