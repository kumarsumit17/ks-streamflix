package com.ks.streamflix.plugins.subtitles

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.SubtitleList
import com.ks.streamflix.api.SubtitlesApi
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitlesPluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)
    private val srt = "﻿1\r\n00:00:00,500 --> 00:00:01,500\r\nHello\r\n\r\n2\r\n00:00:01,600 --> 00:00:02,000\r\nनमस्ते\r\n"

    @Test fun sideCarNamesAndConversion() {
        val dir = Files.createTempDirectory("subs")
        val video = Files.createFile(dir.resolve("Film (2020).mkv"))
        listOf("Film (2020).srt", "Film (2020).hi.srt", "Film (2020).en.forced.vtt", "Other.srt", "Film (2020) extra.srt").forEach { Files.createFile(dir.resolve(it)) }
        val found = SubtitleFiles.sideCars(video).map { it.path.fileName.toString() to (it.language to it.forced) }
        assertEquals(
            listOf("Film (2020).en.forced.vtt" to ("en" to true), "Film (2020).hi.srt" to ("hi" to false), "Film (2020).srt" to (null to false)),
            found,
        )
        assertEquals("WEBVTT\n\n1\n00:00:00.500 --> 00:00:01.500\nHello\n\n2\n00:00:01.600 --> 00:00:02.000\nनमस्ते\n", SubtitleFiles.srtToVtt(srt))
        assertEquals("Hindi (forced)", SubtitleFiles.label("hi", null, true, "x"))
    }

    @Test fun listsAndServesSideCarAndEmbeddedTracks() {
        val ff = Ffmpeg.locate()
        val media = Files.createTempDirectory("sf-media")
        Files.writeString(media.resolve("Film (2020).hi.srt"), srt)
        val withEmbedded = ff.ffmpeg != null && ff.ffprobe != null
        val video = media.resolve("Film (2020).mkv")
        if (withEmbedded) {
            val source = media.resolve("in.srt").also { Files.writeString(it, srt) }
            runBlocking {
                ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10", "-i", source.toString(), "-t", "2", "-map", "0:v", "-map", "1:s",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:s", "srt", "-metadata:s:s:0", "language=eng", "-y", video.toString()))
            }
            Files.delete(source)
        } else {
            Files.write(video, ByteArray(100))
        }
        TestServer(plugins = listOf(SubtitlesPlugin(ff))).use { s ->
            s.setupOwner()
            val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val item = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.single()
            val tracks = s.send(SubtitlesApi.list(item.id)).body().decode<SubtitleList>().tracks
            assertEquals("Hindi", tracks.first().label)
            val vtt = s.send(tracks.first().path)
            assertEquals("text/vtt; charset=utf-8", vtt.headers().firstValue("Content-Type").get())
            assertTrue(vtt.body().startsWith("WEBVTT") && "नमस्ते" in vtt.body())
            if (withEmbedded) {
                val embedded = tracks.single { it.id.startsWith("stream-") }
                assertEquals("English", embedded.label)
                val body = s.send(embedded.path).body()
                assertTrue(body.startsWith("WEBVTT") && "Hello" in body, body)
            }
            assertEquals(404, s.send(SubtitlesApi.track(item.id, "file-9")).statusCode())
            s.token = null
            assertEquals(401, s.send(tracks.first().path).statusCode())
        }
    }
}
