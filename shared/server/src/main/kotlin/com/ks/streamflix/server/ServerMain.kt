package com.ks.streamflix.server

import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The command line shared by the macOS and Windows entry points:
 * `[--port N] [--bind HOST] [--data DIR] [--name NAME] [--library Name=movies|shows:FOLDER]...`
 */
object ServerMain {
    fun run(args: Array<String>, defaultDataDir: Path, plugins: List<ServerPlugin>) {
        var config = ServerConfig(dataDir = defaultDataDir)
        val libraries = mutableListOf<Triple<String, LibraryKind, String>>()
        val it = args.iterator()
        fun value(flag: String) = if (it.hasNext()) it.next() else usage("$flag needs a value")
        while (it.hasNext()) {
            when (val arg = it.next()) {
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
                else -> usage("Unknown option $arg")
            }
        }
        val server = StreamflixServer(config, plugins)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        runBlocking {
            libraries.forEach { (name, kind, folder) -> server.ensureLibrary(name, kind, folder) }
        }
        val port = server.start()
        println("KS StreamFlix ${config.version} is running: http://localhost:$port/  (data: ${config.dataDir})")
        runBlocking {
            server.libraries.libraries().forEach { library ->
                val result = server.libraries.scan(library.id)
                println("Scanned ${library.name}: ${result.added} added, ${result.updated} updated, ${result.removed} removed")
            }
        }
        Thread.currentThread().join()
    }

    private fun usage(problem: String?): Nothing {
        problem?.let { System.err.println(it) }
        System.err.println(
            "Usage: [--port N] [--bind HOST] [--data DIR] [--name NAME] [--library Name=movies|shows:FOLDER]...",
        )
        exitProcess(if (problem == null) 0 else 2)
    }
}
