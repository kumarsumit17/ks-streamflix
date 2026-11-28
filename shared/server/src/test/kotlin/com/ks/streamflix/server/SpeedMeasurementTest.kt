package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ScanResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Speed measurements (specs/quality-budgets.md): recorded for the report, with no limit. */
class SpeedMeasurementTest {

    @Test fun startUpTime() {
        val data = Files.createTempDirectory("sf-data")
        val started = System.nanoTime()
        TestServer(data).use { s ->
            assertEquals(200, s.send(ApiV1.INFO).statusCode())
            val ms = (System.nanoTime() - started) / 1_000_000
            Measurement.record("server.start", "$ms ms")
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
        Measurement.record("scan.rate", "$rate files/s")
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
        // Five rounds of 50 request pairs; the fastest round's p95 is reported, because a shared CI
        // machine (other jobs, the disk, a GC) spoils rounds at random.
        val rounds = (1..5).map {
            val times = (1..50).flatMap {
                listOf(
                    timed { s.send("${ApiV1.ITEMS}?library=${library.id}").statusCode() },
                    timed { s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-0")).statusCode() },
                )
            }.sorted()
            times[(times.size * 95) / 100 - 1]
        }
        Measurement.record("api.p95", "${rounds.min()} ms (rounds: ${rounds.joinToString()})")
    }

    private fun timed(block: () -> Int): Long {
        val t = System.nanoTime()
        check(block() in 200..299)
        return (System.nanoTime() - t) / 1_000_000
    }
}
