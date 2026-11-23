package com.ks.streamflix.server

import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The command line shared by the macOS and Windows apps:
 * `[--headless] [--port N] [--bind HOST] [--data DIR] [--name NAME] [--library Name=movies|shows:FOLDER]...`
 */
object ServerMain {
    class Options(val config: ServerConfig, val libraries: List<Triple<String, LibraryKind, String>>, val headless: Boolean)

    fun parse(args: Array<String>, defaultDataDir: Path): Options {
        var config = ServerConfig(dataDir = defaultDataDir)
        var headless = false
        val libraries = mutableListOf<Triple<String, LibraryKind, String>>()
        val it = args.iterator()
        fun value(flag: String) = if (it.hasNext()) it.next() else usage("$flag needs a value")
        while (it.hasNext()) {
            when (val arg = it.next()) {
                "--headless" -> headless = true
                "--port" -> config = config.copy(port = value(arg).toIntOrNull() ?: usage("--port needs a number"))
                "--bind" -> config = config.copy(host = value(arg))
                "--data" -> config = config.copy(dataDir = Paths.get(value(arg)))
                "--name" -> config = config.copy(name = value(arg))
                "--library" -> {
                    val spec = value(arg)
                    val name = spec.substringBefore('=', "")
                    val kind = when (spec.substringAfter('=').substringBefore(':')) {
                        "movies" -> LibraryKind.MOVIES
                        "shows" -> LibraryKind.SHOWS
                        else -> usage("--library needs Name=movies:FOLDER or Name=shows:FOLDER")
                    }
                    val folder = spec.substringAfter('=').substringAfter(':', "")
                    if (name.isEmpty() || folder.isEmpty()) usage("--library needs Name=movies:FOLDER")
                    libraries += Triple(name, kind, folder)
                }
                "--help", "-h" -> usage(null)
                // macOS adds -psn_... when an app is opened from Finder.
                else -> if (!arg.startsWith("-psn_")) usage("Unknown option $arg")
            }
        }
        return Options(config, libraries, headless)
    }

    /** Starts the server, adds the command line's libraries, and scans every library in the background. */
    fun start(options: Options, plugins: List<ServerPlugin>, log: (String) -> Unit = ::println): Pair<StreamflixServer, Int> {
        val server = StreamflixServer(options.config, plugins)
        runBlocking { options.libraries.forEach { (name, kind, folder) -> server.ensureLibrary(name, kind, folder) } }
        val port = server.start()
        log("KS StreamFlix ${options.config.version} is running: http://localhost:$port/  (data: ${options.config.dataDir})")
        Thread({
            runBlocking {
                server.libraries.libraries().forEach { library ->
                    runCatching { server.libraries.scan(library.id) }.onSuccess {
                        log("Scanned ${library.name}: ${it.added} added, ${it.updated} updated, ${it.removed} removed")
                    }
                }
            }
        }, "streamflix-startup-scan").apply { isDaemon = true }.start()
        return server to port
    }

    /** Runs the server without a window until the process is stopped. */
    fun runHeadless(options: Options, plugins: List<ServerPlugin>) {
        val (server, _) = start(options, plugins)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        Thread.currentThread().join()
    }

    private fun usage(problem: String?): Nothing {
        problem?.let { System.err.println(it) }
        System.err.println(
            "Usage: [--headless] [--port N] [--bind HOST] [--data DIR] [--name NAME] [--library Name=movies|shows:FOLDER]...",
        )
        exitProcess(if (problem == null) 0 else 2)
    }
}
