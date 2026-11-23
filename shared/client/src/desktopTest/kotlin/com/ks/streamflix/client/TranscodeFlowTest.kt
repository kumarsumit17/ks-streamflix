package com.ks.streamflix.client

import androidx.compose.runtime.Composable
import com.ks.streamflix.api.DeviceProfile
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.settings.MemorySettings
import com.ks.streamflix.client.settings.SettingKeys
import com.ks.streamflix.client.settings.SettingsStore
import com.ks.streamflix.client.ui.AppViewModel
import com.ks.streamflix.client.ui.Screen
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.plugins.transcode.TranscodePlugin
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.ks.streamflix.client.plugin.TranscodePlugin as TranscodeClient

/** The apps' side of transcoding against the real server and FFmpeg (skipped without FFmpeg). */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscodeFlowTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(main)
    @AfterTest fun tearDown() { Dispatchers.resetMain(); main.close() }

    private class Host(override val settings: SettingsStore, override val deviceProfile: DeviceProfile) : PlatformHost {
        override val isTv = false
        override val transport: HttpTransport = UrlConnectionTransport()
        override val preferredLanguages = listOf("en")
        override val appVersion = "test"
        override val hasPlayer = true
        override suspend fun discover() = emptyList<FoundServer>()
        @Composable override fun Player(request: PlayRequest, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) = Unit
        override fun openExternally(url: String) = false
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }; Thread.sleep(20) }
    }

    @Test fun remuxThenRestartThenStop() {
        val ff = Ffmpeg.locate()
        if (ff.ffmpeg == null || ff.ffprobe == null) return
        val media = Files.createTempDirectory("sf-media")
        runBlocking {
            ff.ffmpegOutput(listOf("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=f=440", "-t", "20",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "50", "-c:a", "aac", "-shortest", "-y", media.resolve("Film (2020).mkv").toString()))
        }
        val server = TranscodePlugin(ff)
        TestServer(plugins = listOf(server)).use { s ->
            s.setupOwner()
            runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val settings = MemorySettings().apply { putString(SettingKeys.SERVER, s.base); putString(SettingKeys.TOKEN, s.token) }
            val chromeLike = DeviceProfile(listOf("mp4"), listOf("h264"), listOf("aac"))
            val vm = AppViewModel(Host(settings, chromeLike), listOf(TranscodeClient()))
            waitFor("home") { vm.state.value.screen == Screen.Home }
            val film = vm.state.value.rows.single().items.single()

            vm.play(film)
            waitFor("player") { vm.state.value.screen is Screen.Playing }
            val request = (vm.state.value.screen as Screen.Playing).request
            assertTrue(request.hls && request.url.endsWith("/index.m3u8"), request.url)
            assertEquals(1, server.running)

            // Playing it again replaces the session instead of adding one.
            vm.back()
            vm.play(film, 12_000)
            waitFor("player again") { (vm.state.value.screen as? Screen.Playing)?.request?.let { it.startMs == 12_000L && it.url != request.url } == true }
            assertEquals(1, server.running)

            vm.playbackStopped(film, 13_000, 20_000)
            waitFor("session stopped") { server.running == 0 }
        }
    }
}
