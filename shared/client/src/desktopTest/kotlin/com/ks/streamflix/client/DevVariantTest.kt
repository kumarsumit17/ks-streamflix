package com.ks.streamflix.client

import androidx.compose.runtime.Composable
import com.ks.streamflix.api.DiscoveryApi
import com.ks.streamflix.client.net.HttpTransport
import com.ks.streamflix.client.settings.MemorySettings
import com.ks.streamflix.client.settings.SettingKeys
import com.ks.streamflix.client.settings.SettingsStore
import com.ks.streamflix.client.ui.AppViewModel
import com.ks.streamflix.client.ui.Screen
import com.ks.streamflix.server.ServerMain
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Paths
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A DEV build runs next to production and shares nothing with it (specs/dev-variant.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class DevVariantTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)
    @AfterTest fun tearDown() { Dispatchers.resetMain(); main.close() }

    private class Host(override val isDev: Boolean) : PlatformHost {
        override val settings: SettingsStore = MemorySettings()
        override val isTv = false
        override val transport: HttpTransport = UrlConnectionTransport()
        override val preferredLanguages = listOf("en")
        override val appVersion = "test"
        override val hasPlayer = true
        override suspend fun discover() = emptyList<FoundServer>()
        @Composable override fun Player(request: PlayRequest, onProgress: (Long, Long) -> Unit, onExit: (Long, Long) -> Unit) = Unit
        override fun openExternally(url: String) = true
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test fun productionKeepsItsIdentityAndDevSharesNothing() {
        val prod = DesktopVariant(dev = false)
        // Production's identities never change.
        assertEquals("KS StreamFlix", prod.appName)
        assertEquals("com.ks.streamflix", prod.id)
        assertEquals("com/ks/streamflix/client", prod.settingsNode)
        assertEquals(8742, prod.port)
        val dev = DesktopVariant(dev = true)
        assertEquals("KS StreamFlix DEV", dev.appName)
        assertEquals("com.ks.streamflix.dev", dev.id)
        assertFalse(dev.settingsNode.startsWith("com/ks/streamflix/"), "never inside production's node")
        assertNotEquals(prod.port, dev.port)
    }

    @Test fun aDevServerHasItsOwnPortNameAndMark() {
        val data = Paths.get("data")
        val prod = ServerMain.parse(emptyArray(), data).config
        assertEquals(DiscoveryApi.PORT, prod.port)
        assertFalse(prod.dev)
        val dev = ServerMain.parse(emptyArray(), data, dev = true).config
        assertEquals(DiscoveryApi.DEV_PORT, dev.port)
        assertTrue(dev.dev)
        assertEquals("${prod.name} DEV", dev.name)
        assertEquals(9000, ServerMain.parse(arrayOf("--port", "9000"), data, dev = true).config.port)
    }

    @Test fun aDevAppRefusesAProductionServer() = TestServer().use { s ->
        s.setupOwner()
        val host = Host(isDev = true)
        val vm = AppViewModel(host, emptyList())
        waitFor("connect screen") { vm.state.value.screen == Screen.Connect }
        vm.connect(s.base)
        waitFor("refusal") { vm.state.value.error == "client.error.devOnly" }
        assertNull(host.settings.getString(SettingKeys.SERVER))
    }

    @Test fun aDevAppConnectsToADevServer() = TestServer(dev = true).use { s ->
        s.setupOwner()
        val vm = AppViewModel(Host(isDev = true), emptyList())
        waitFor("connect screen") { vm.state.value.screen == Screen.Connect }
        vm.connect(s.base)
        waitFor("sign-in screen") { vm.state.value.screen == Screen.SignIn }
    }
}
