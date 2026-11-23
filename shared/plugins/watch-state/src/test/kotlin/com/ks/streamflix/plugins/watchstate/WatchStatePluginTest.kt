package com.ks.streamflix.plugins.watchstate

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ContinueList
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ProgressList
import com.ks.streamflix.api.ProgressUpdate
import com.ks.streamflix.api.WatchProgress
import com.ks.streamflix.api.WatchStateApi
import com.ks.streamflix.api.WatchedUpdate
import com.ks.streamflix.server.TestServer
import com.ks.streamflix.server.mediaFolder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchStatePluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    @Test fun progressContinueWatchedAndRestart() {
        val data = Files.createTempDirectory("sf-data")
        val media = mediaFolder("A (2001).mp4" to 10, "B (2002).mp4" to 10)
        var library = 0L
        TestServer(data, listOf(WatchStatePlugin())).use { s ->
            s.setupOwner()
            library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }.id
            s.send(ApiV1.libraryScan(library), "POST")
            val (a, b) = s.send("${ApiV1.ITEMS}?library=$library").body().decode<ItemList>().items

            val p = s.send(WatchStateApi.item(a.id), "PUT", ApiV1.json.encodeToString(ProgressUpdate(30_000, 100_000)))
            assertEquals(200, p.statusCode(), p.body())
            assertEquals(30_000, p.body().decode<WatchProgress>().positionMs)
            val done = s.send(WatchStateApi.item(b.id), "PUT", ApiV1.json.encodeToString(ProgressUpdate(95_000, 100_000))).body().decode<WatchProgress>()
            assertTrue(done.watched, "90% counts as watched")
            assertEquals(0, done.positionMs)

            assertEquals(listOf(a.id), s.send(WatchStateApi.CONTINUE).body().decode<ContinueList>().items.map { it.item.id })
            assertEquals(2, s.send(WatchStateApi.progress(library)).body().decode<ProgressList>().progress.size)
            assertEquals(404, s.send(WatchStateApi.item(9_999), "PUT", ApiV1.json.encodeToString(ProgressUpdate(1, 2))).statusCode())
            assertEquals(false, s.send(WatchStateApi.watched(b.id), "POST", ApiV1.json.encodeToString(WatchedUpdate(false))).body().decode<WatchProgress>().watched)
        }
        TestServer(data, listOf(WatchStatePlugin())).use { s ->
            s.token = runBlocking { s.server.auth.signIn("owner", "secret-pass", "t").first }
            assertEquals(1, s.send(WatchStateApi.CONTINUE).body().decode<ContinueList>().items.size, "progress survives a restart")
            s.token = null
            assertEquals(401, s.send(WatchStateApi.CONTINUE).statusCode())
        }
    }
}
