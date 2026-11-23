package com.ks.streamflix.plugins.mediainfo

import com.ks.streamflix.api.ApiError
import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.MediaInfoApi
import com.ks.streamflix.api.ToolStatus
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaInfoPluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    /** A 2 s film with two audio tracks, made with the real FFmpeg; null where it is not installed. */
    private fun film(dir: Path, ff: Ffmpeg): Path? {
        val out = dir.resolve("Film (2020).mkv")
        val ok = runBlocking {
            ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-f", "lavfi", "-i", "sine=f=440", "-f", "lavfi", "-i", "sine=f=660",
                "-t", "2", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=hin", "-y", out.toString()))
        }
        return out.takeIf { ok != null }
    }

    @Test fun readsTracksWithFfprobe() {
        val ff = Ffmpeg.locate()
        if (ff.ffprobe == null || ff.ffmpeg == null) return
        val media = Files.createTempDirectory("sf-media")
        film(media, ff) ?: return
        TestServer(plugins = listOf(MediaInfoPlugin(ff))).use { s ->
            s.setupOwner()
            val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val item = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.single()
            assertEquals(true, s.send(MediaInfoApi.STATUS).body().decode<ToolStatus>().ffprobe)
            val info = s.send(MediaInfoApi.item(item.id)).body().decode<MediaInfo>()
            assertEquals(320, info.video!!.width)
            assertEquals(listOf("eng", "hin"), info.audio.map { it.language })
            // The second answer comes from the plugin's own cache.
            assertEquals(info, s.send(MediaInfoApi.item(item.id)).body().decode<MediaInfo>())
        }
    }

    @Test fun withoutFfmpegItSaysSo() = TestServer(plugins = listOf(MediaInfoPlugin(Ffmpeg(null, null)))).use { s ->
        s.setupOwner()
        val media = com.ks.streamflix.server.mediaFolder("Film (2020).mkv" to 100)
        val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
        val item = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.single()
        assertEquals(ToolStatus(false, false, null), s.send(MediaInfoApi.STATUS).body().decode<ToolStatus>())
        val r = s.send(MediaInfoApi.item(item.id))
        assertEquals(404, r.statusCode())
        assertEquals("mediaInfo.error.unavailable", r.body().decode<ApiError>().code)
    }
}
