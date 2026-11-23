package com.ks.streamflix.client

import androidx.compose.runtime.Composable
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.plugin.WatchStatePlugin as WatchStateClient
import com.ks.streamflix.client.settings.MemorySettings
import com.ks.streamflix.client.settings.SettingKeys
import com.ks.streamflix.client.settings.SettingsStore
import com.ks.streamflix.client.ui.AppViewModel
import com.ks.streamflix.client.ui.Screen
import com.ks.streamflix.plugins.watchstate.WatchStatePlugin
import com.ks.streamflix.server.TestServer
import com.ks.streamflix.server.mediaFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The apps' whole flow against a real server: connect, sign in, browse, play, resume. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppFlowTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)
    @AfterTest fun tearDown() { Dispatchers.resetMain(); main.close() }

    private class TestHost(override val settings: SettingsStore, var played: String? = null) : PlatformHost {
        override val isTv = false
        override val transport: HttpTransport = UrlConnectionTransport()
        override val preferredLanguages = listOf("en")
        override val appVersion = "test"
        override val hasPlayer = true
        override suspend fun discover() = emptyList<FoundServer>()
        @Composable override fun Player(request: PlayRequest, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) = Unit
        override fun openExternally(url: String): Boolean { played = url; return true }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test fun connectSignInBrowsePlayResume() = TestServer(plugins = listOf(WatchStatePlugin())).use { s ->
        s.setupOwner()
        val media = mediaFolder("The Long Road (2019).mp4" to 1000, "Quiet Hills (2008).mkv" to 1000)
        runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }

        val settings = MemorySettings()
        val host = TestHost(settings)
        val vm = AppViewModel(host, listOf(WatchStateClient()))
        waitFor("connect screen") { vm.state.value.screen == Screen.Connect }

        vm.connect("127.0.0.1:1") // nothing listens there
        waitFor("unreachable error") { vm.state.value.error == "client.error.unreachable" }

        vm.connect(s.base)
        waitFor("sign-in screen") { vm.state.value.screen == Screen.SignIn }
        assertEquals("test", vm.state.value.serverName)

        vm.signIn("owner", "wrong-pass")
        waitFor("wrong password") { vm.state.value.error == "auth.error.wrong" }
        vm.signIn("owner", "secret-pass")
        waitFor("home") { vm.state.value.screen == Screen.Home }
        assertEquals(listOf("Quiet Hills", "The Long Road"), vm.state.value.rows.single().items.map { it.title })
        assertTrue(settings.getString(SettingKeys.TOKEN) != null, "the session is remembered")

        val film = vm.state.value.rows.single().items.last()
        vm.openItem(film)
        waitFor("detail") { vm.state.value.screen is Screen.Detail }
        vm.play(film)
        val playing = vm.state.value.screen as Screen.Playing
        assertEquals("${s.base}/api/v1/items/${film.id}/stream", playing.request.url)
        assertTrue(playing.request.headers["Authorization"]!!.startsWith("Bearer "))

        // The player stops at 1:40 of 10:00: the watch-state plugin saves it, and Resume offers it.
        vm.playbackStopped(film, 100_000, 600_000)
        waitFor("Resume on the detail page") { vm.state.value.screen is Screen.Detail && vm.state.value.resumeMs == 100_000L }
        vm.back()
        waitFor("home again") { vm.state.value.screen == Screen.Home }
        vm.openItem(film)
        waitFor("resume position") { vm.state.value.resumeMs == 100_000L }

        // A new start of the app goes straight to the home screen with the saved session.
        val again = AppViewModel(TestHost(settings), listOf(WatchStateClient()))
        waitFor("home on restart") { again.state.value.screen == Screen.Home }

        vm.signOut()
        waitFor("signed out") { vm.state.value.screen == Screen.SignIn && settings.getString(SettingKeys.TOKEN) == null }
    }

    @Test fun withoutAPlayerVideosOpenThroughAStreamLink() = TestServer().use { s ->
        s.setupOwner()
        val media = mediaFolder("Film (2020).mp4" to 1000)
        runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
        val settings = MemorySettings().apply { putString(SettingKeys.SERVER, s.base); putString(SettingKeys.TOKEN, s.token) }
        val host = object : PlatformHost by TestHost(settings) { override val hasPlayer = false; var url: String? = null
            override fun openExternally(url: String): Boolean { this.url = url; return true } }
        val vm = AppViewModel(host, emptyList())
        waitFor("home") { vm.state.value.screen == Screen.Home }
        vm.play(vm.state.value.rows.single().items.single())
        waitFor("stream link opened") { host.url != null }
        assertTrue(host.url!!.contains("/stream?key="), host.url)
        val status = (java.net.URI(host.url!!).toURL().openConnection() as java.net.HttpURLConnection).responseCode
        assertEquals(200, status, "the link works without the sign-in header")
    }
}
