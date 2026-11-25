package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ScanResult
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

/** Speed and stability budgets (specs/quality-budgets.md). The numbers come from quality-budgets.properties. */
class QualityBudgetTest {

    @Test fun startUpTime() {
        val data = Files.createTempDirectory("sf-data")
        val started = System.nanoTime()
        TestServer(data).use { s ->
            assertEquals(200, s.send(ApiV1.INFO).statusCode())
            val ms = (System.nanoTime() - started) / 1_000_000
            println("budget speed.server.startMs: $ms ms")
            assertTrue(ms <= Budget.long("speed.server.startMs"), "start-up took $ms ms")
        }
    }

    @Test fun scanSpeed() = TestServer().use { s ->
        val root = Files.createTempDirectory("sf-media")
        val count = 2_000
        repeat(count) { i -> Files.createFile(root.resolve("Film number $i (${1950 + i % 70}).mkv")) }
        val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, root.toString()) }
        s.setupOwner()
        val started = System.nanoTime()
        val result = ApiV1.json.decodeFromString<ScanResult>(s.send(ApiV1.libraryScan(library.id), "POST").body())
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(count, result.added)
        val rate = (count / seconds).toLong()
        println("budget speed.scan.filesPerSecond: $rate files/s")
        assertTrue(rate >= Budget.long("speed.scan.filesPerSecond"), "scanned $rate files/s")
    }

    @Test fun apiLatency() = TestServer().use { s ->
        s.setupOwner()
        val media = mediaFolder(*Array(200) { "Film $it (2000).mp4" to 50_000 })
        val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
        s.send(ApiV1.libraryScan(library.id), "POST")
        val id = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${library.id}").body()).items.first().id
        // Warm-up of both kinds of request (the JIT, and the first file opens, which are slow on Windows).
        repeat(20) {
            s.send("${ApiV1.ITEMS}?library=${library.id}")
            s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-0"))
        }
        // Five rounds of 50 request pairs; the budget is checked against the median round's p95.
        // A slower server is slower in every round, while a pause on a shared CI machine
        // (another job, the disk, a GC) spoils one or two rounds, not three.
        val rounds = (1..5).map {
            val times = (1..50).flatMap {
                listOf(
                    timed { s.send("${ApiV1.ITEMS}?library=${library.id}").statusCode() },
                    timed { s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-0")).statusCode() },
                )
            }.sorted()
            times[(times.size * 95) / 100 - 1]
        }
        val p95 = rounds.sorted()[rounds.size / 2]
        println("budget speed.api.p95Ms: $p95 ms (rounds: ${rounds.joinToString()})")
        assertTrue(p95 <= Budget.long("speed.api.p95Ms"), "p95 was $p95 ms")
    }

    @Test fun concurrentStreams() = TestServer().use { s ->
        s.setupOwner()
        val size = 2_000_000
        val media = mediaFolder("Big (2020).mp4" to size)
        val original = Files.readAllBytes(media.resolve("Big (2020).mp4"))
        val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
        s.send(ApiV1.libraryScan(library.id), "POST")
        val id = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${library.id}").body()).items.single().id
        val n = Budget.long("stability.concurrentStreams").toInt()
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
        val runs = Budget.long("stability.repeatRuns").toInt()
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

    private fun timed(block: () -> Int): Long {
        val t = System.nanoTime()
        check(block() in 200..299)
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun openFiles(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)?.openFileDescriptorCount ?: 0
}
