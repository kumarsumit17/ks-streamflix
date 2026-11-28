package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONCURRENT_STREAMS = 16
private const val REPEAT_RUNS = 5

/** Stability: parallel streams are all correct, and repeated runs leave no file open. */
class StabilityTest {

    @Test fun concurrentStreams() = TestServer().use { s ->
        s.setupOwner()
        val size = 2_000_000
        val media = mediaFolder("Big (2020).mp4" to size)
        val original = Files.readAllBytes(media.resolve("Big (2020).mp4"))
        val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
        s.send(ApiV1.libraryScan(library.id), "POST")
        val id = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${library.id}").body()).items.single().id
        val n = CONCURRENT_STREAMS
        val chunk = size / n
        val futures = (0 until n).map { i ->
            val range = "bytes=${i * chunk}-${(i + 1) * chunk - 1}"
            s.http.sendAsync(s.request(ApiV1.stream(id), headers = mapOf("Range" to range)), HttpResponse.BodyHandlers.ofByteArray())
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        futures.forEachIndexed { i, f ->
            val r = f.join()
            assertEquals(206, r.statusCode())
            assertContentEquals(original.copyOfRange(i * chunk, (i + 1) * chunk), r.body())
        }
    }

    @Test fun repeatedRunsLeakNothing() {
        val data = Files.createTempDirectory("sf-data")
        val media = mediaFolder("Film (2020).mp4" to 100_000)
        val runs = REPEAT_RUNS
        val counts = (1..runs).map { run ->
            TestServer(data).use { s ->
                if (run == 1) s.setupOwner() else s.token = runBlocking { s.server.auth.signIn("owner", "secret-pass", "test").first }
                val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
                s.send(ApiV1.libraryScan(library.id), "POST")
                val id = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${library.id}").body()).items.single().id
                assertEquals(206, s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-999")).statusCode())
            }
            openFiles()
        }
        println("open files after each run: $counts")
        // Each run may warm a cache once; after that the count must not keep growing.
        assertTrue(counts.last() - counts[1] <= 5, "open files grew: $counts")
    }

    private fun openFiles(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)?.openFileDescriptorCount ?: 0
}
