package com.ks.streamflix.server

import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ServerInfo
import com.ks.streamflix.server.data.Store
import com.ks.streamflix.server.domain.AuthService
import com.ks.streamflix.server.domain.LibraryService
import com.ks.streamflix.server.domain.Passwords
import com.ks.streamflix.server.domain.StreamKeys
import com.ks.streamflix.server.details.DetailsService
import com.ks.streamflix.server.http.coreModule
import com.ks.streamflix.server.lang.Strings
import com.ks.streamflix.server.plugin.Core
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

data class ServerConfig(
    val dataDir: Path,
    val port: Int = DEFAULT_PORT,
    /** `0.0.0.0` serves the home network; `127.0.0.1` only this computer. */
    val host: String = "0.0.0.0",
    val name: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("KS StreamFlix"),
    val version: String = System.getProperty("streamflix.version") ?: "0.0.0",
    val passwordIterations: Int = Passwords.DEFAULT_ITERATIONS,
    /** A DEV build's server (specs/dev-variant.md). */
    val dev: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 8742
    }
}

/** The server: the core plus the [plugins] an app includes. */
class StreamflixServer(private val config: ServerConfig, plugins: List<ServerPlugin>) : AutoCloseable {
    private val store: Store
    val auth: AuthService
    val libraries: LibraryService
    val strings: Strings
    private val engine: EmbeddedServer<*, *>
    private val contexts: List<PluginContext>
    /** Whether a stream is being watched (the desktop app keeps the computer awake meanwhile). */
    val streaming: com.ks.streamflix.server.domain.StreamActivity
    /** The server's name, shown by clients. */
    val name: String get() = config.name

    init {
        val ids = plugins.map { it.id }
        require(ids.size == ids.toSet().size) { "Two plugins have the same id: $ids" }
        Files.createDirectories(config.dataDir)
        store = Store.open(config.dataDir.resolve("streamflix.db"))
        auth = AuthService(store, config.passwordIterations)
        libraries = LibraryService(store)
        strings = Strings(listOf("common", "core") + ids)
        val core = Core(auth, libraries, StreamKeys(config.dataDir.resolve("stream-key")), DetailsService(config.dataDir.resolve("cache/images"))) { ServerInfo(config.name, config.version, setupNeeded = auth.setupNeeded(), dev = config.dev) }
        streaming = core.streaming
        contexts = plugins.map { plugin ->
            PluginContext(core, strings, config.dataDir.resolve("plugins").resolve(plugin.id)).also(plugin::install)
        }
        val routes = contexts.flatMap { it.routes }
        engine = embeddedServer(CIO, configure = {
            connector {
                host = config.host
                port = config.port
            }
        }) { coreModule(core, routes) }
    }

    /** Starts listening and returns the port (useful when [ServerConfig.port] is 0). */
    fun start(): Int {
        engine.start(wait = false)
        val port = runBlocking { engine.engine.resolvedConnectors().first().port }
        contexts.forEach { c -> c.startListeners.forEach { it(port) } }
        return port
    }

    /** Adds the library [name] unless it exists. For the command line and tests. */
    suspend fun ensureLibrary(name: String, kind: LibraryKind, folder: String) =
        libraries.libraries().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: libraries.addLibrary(name, kind, listOf(folder))

    override fun close() {
        contexts.forEach { c -> c.stopListeners.forEach { runCatching(it) } }
        engine.stop(gracePeriodMillis = 200, timeoutMillis = 2_000)
        store.close()
    }
}
