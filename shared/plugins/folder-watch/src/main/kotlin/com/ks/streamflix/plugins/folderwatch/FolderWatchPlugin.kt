package com.ks.streamflix.plugins.folderwatch

import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * Plugin `folder-watch`: watches every library folder (and the folders inside) and rescans a library
 * [quietMillis] after its files stop changing, so a film copied in appears without a manual scan.
 * New libraries are picked up within a minute; every library is also rescanned every
 * [fullRescanMillis], for changes the system did not report (network drives).
 */
class FolderWatchPlugin(
    private val quietMillis: Long = 10_000,
    private val fullRescanMillis: Long = 6 * 60 * 60 * 1000L,
    private val libraryCheckMillis: Long = 60_000,
) : ServerPlugin {
    override val id = "folder-watch"

    override fun install(context: PluginContext) {
        val libraries = context.core.libraries
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var watcher: WatchService? = null
        val keys = ConcurrentHashMap<WatchKey, Pair<Long, Path>>() // key -> (library, folder)
        val watched = ConcurrentHashMap.newKeySet<Path>()
        val pending = ConcurrentHashMap<Long, Job>()

        fun register(libraryId: Long, root: Path) {
            val w = watcher ?: return
            if (!root.isDirectory()) return
            runCatching {
                Files.walk(root).use { s ->
                    s.filter { it.isDirectory() && watched.add(it) }.forEach { dir ->
                        // HIGH sensitivity: the JDK polls on macOS, every 2 s instead of 10 s.
                        val key = runCatching {
                            dir.register(w, arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), com.sun.nio.file.SensitivityWatchEventModifier.HIGH)
                        }.getOrElse { dir.register(w, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
                        keys[key] = libraryId to dir
                    }
                }
            }
        }

        /** A rescan once the library has been quiet for a moment (a copy changes a file many times). */
        fun scheduleScan(libraryId: Long) {
            pending.put(libraryId, scope.launch {
                delay(quietMillis)
                runCatching { libraries.scan(libraryId) }
                pending.remove(libraryId)
            })?.cancel()
        }

        context.onStarted {
            watcher = FileSystems.getDefault().newWatchService()
            scope.launch {
                while (isActive) {
                    runCatching { libraries.libraries().forEach { lib -> lib.folders.forEach { register(lib.id, Paths.get(it)) } } }
                    delay(libraryCheckMillis)
                }
            }
            scope.launch(Dispatchers.IO) {
                val w = watcher ?: return@launch
                while (isActive) {
                    val key = try { w.poll(1, TimeUnit.SECONDS) } catch (_: ClosedWatchServiceException) { break } ?: continue
                    val (libraryId, dir) = keys[key] ?: run { key.cancel(); null } ?: continue
                    key.pollEvents().forEach { event ->
                        val child = (event.context() as? Path)?.let(dir::resolve)
                        // A new folder is watched too (a film copied in with its own folder).
                        if (event.kind() == ENTRY_CREATE && child != null && child.isDirectory()) register(libraryId, child)
                    }
                    if (!key.reset()) { keys.remove(key); watched.remove(dir) }
                    scheduleScan(libraryId)
                }
            }
            scope.launch {
                while (isActive) {
                    delay(fullRescanMillis)
                    runCatching { libraries.libraries().forEach { libraries.scan(it.id) } }
                }
            }
        }
        context.onStopped {
            scope.cancel()
            runCatching { watcher?.close() }
        }
    }
}
