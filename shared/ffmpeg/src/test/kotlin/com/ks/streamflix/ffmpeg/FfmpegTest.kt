package com.ks.streamflix.ffmpeg

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegTest {
    private val sample = javaClass.getResource("/probe-sample.json")!!.readText()

    @Test fun parsesRecordedProbe() {
        val info = Ffmpeg.parseProbe(7, sample)
        assertEquals(7, info.itemId)
        assertEquals(5023L, info.durationMs) // 5 s of video; the AAC audio runs 23 ms longer
        assertEquals("h264", info.video!!.codec)
        assertEquals(320 to 180, info.video!!.width to info.video!!.height)
        assertEquals(25.0, info.video!!.frameRate)
        assertEquals(listOf("eng", "hin"), info.audio.map { it.language })
        val sub = info.subtitles.single()
        assertEquals("subrip", sub.codec)
        assertEquals("hin", sub.language)
        assertEquals("Hindi", sub.title)
        assertTrue(sub.default)
    }

    @Test fun locatesOnlyWhatExists() {
        val dir = Files.createTempDirectory("ff").toFile()
        val probe = File(dir, "ffprobe").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val found = Ffmpeg.locate(mapOf("STREAMFLIX_FFMPEG_DIR" to dir.path, "PATH" to ""), windows = false)
        assertEquals(probe, found.ffprobe)
        val none = Ffmpeg.locate(mapOf("PATH" to dir.path + "/nothing"), windows = false)
        // The usual places may hold a real FFmpeg on this machine; a missing folder alone finds nothing.
        if (!File("/opt/homebrew/bin/ffprobe").exists() && !File("/usr/local/bin/ffprobe").exists() && !File("/usr/bin/ffprobe").exists()) assertNull(none.ffprobe)
    }

    /** Runs only where FFmpeg is installed (the owner's Mac, CI with FFmpeg). */
    @Test fun realProbeWhenInstalled() {
        val ff = Ffmpeg.locate()
        if (ff.ffprobe == null || ff.ffmpeg == null) return
        val dir = Files.createTempDirectory("ffreal")
        val file = dir.resolve("t.mp4")
        val made = kotlinx.coroutines.runBlocking {
            ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10", "-t", "1", "-pix_fmt", "yuv420p", "-y", file.toString()))
        }
        assertNotNull(made)
        val info = kotlinx.coroutines.runBlocking { ff.probe(1, file) }!!
        assertEquals(160, info.video!!.width)
        assertTrue(info.durationMs!! in 900..1100)
        assertNotNull(ff.version)
    }
}
