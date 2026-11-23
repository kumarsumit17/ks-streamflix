package com.ks.streamflix.plugins.folderwatch

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.Budget
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

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
            check(System.currentTimeMillis() - started < Budget.long("speed.folderWatch.detectMs")) { "the new film did not appear" }
            Thread.sleep(100)
        }
        val ms = System.currentTimeMillis() - started
        println("budget speed.folderWatch.detectMs: $ms ms")

        Files.delete(dir.resolve("new.mkv"))
        val removed = System.currentTimeMillis()
        while ("New Film" in titles()) {
            check(System.currentTimeMillis() - removed < Budget.long("speed.folderWatch.detectMs")) { "the removed film stayed" }
            Thread.sleep(100)
        }
        assertTrue(titles().isEmpty())
    }
}
