package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ScanResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Speed budgets (specs/quality-budgets.md). The numbers come from quality-budgets.properties. */
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
        // Five rounds of 50 request pairs; the budget is checked against the fastest round's p95.
        // A slower server is slower in every round, while a shared CI machine (other jobs, the
        // disk, a GC) spoils rounds at random: Windows runners have measured 45 to 223 ms in one run.
        val rounds = (1..5).map {
            val times = (1..50).flatMap {
                listOf(
                    timed { s.send("${ApiV1.ITEMS}?library=${library.id}").statusCode() },
                    timed { s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-0")).statusCode() },
                )
            }.sorted()
            times[(times.size * 95) / 100 - 1]
        }
        val p95 = rounds.min()
        // Windows has its own budget (quality-budgets.properties); every other system uses the common one.
        val key = if (System.getProperty("os.name").startsWith("Windows")) "speed.api.p95Ms.windows" else "speed.api.p95Ms"
        println("budget $key: $p95 ms (rounds: ${rounds.joinToString()})")
        assertTrue(p95 <= Budget.long(key), "p95 was $p95 ms (budget $key ${Budget.long(key)} ms, rounds: ${rounds.joinToString()})")
    }

    private fun timed(block: () -> Int): Long {
        val t = System.nanoTime()
        check(block() in 200..299)
        return (System.nanoTime() - t) / 1_000_000
    }
}
