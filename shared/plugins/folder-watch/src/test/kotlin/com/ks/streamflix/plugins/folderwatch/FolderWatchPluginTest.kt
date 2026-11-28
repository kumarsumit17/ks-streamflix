package com.ks.streamflix.plugins.folderwatch

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.Measurement
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Only so a broken watcher ends the test: how long detection takes is measured, not limited. */
private const val GIVE_UP_MS = 120_000L

class FolderWatchPluginTest {
    @Test fun newFilesAppearWithoutAScan() = TestServer(plugins = listOf(FolderWatchPlugin(quietMillis = 300, libraryCheckMillis = 200))).use { s ->
        s.setupOwner()
        val root = Files.createTempDirectory("sf-watch")
        val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, root.toString()) }
        Thread.sleep(800) // the plugin picks up the new library
        fun titles() = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${lib.id}").body()).items.map { it.title }

        val started = System.currentTimeMillis()
        val dir = Files.createDirectories(root.resolve("New Film (2024)"))
        Files.write(dir.resolve("new.mkv"), ByteArray(100))
        while ("New Film" !in titles()) {
            check(System.currentTimeMillis() - started < GIVE_UP_MS) { "the new film did not appear" }
            Thread.sleep(100)
        }
        val ms = System.currentTimeMillis() - started
        Measurement.record("folderWatch.detect", "$ms ms")

        Files.delete(dir.resolve("new.mkv"))
        val removed = System.currentTimeMillis()
        while ("New Film" in titles()) {
            check(System.currentTimeMillis() - removed < GIVE_UP_MS) { "the removed film stayed" }
            Thread.sleep(100)
        }
        assertTrue(titles().isEmpty())
    }
}
