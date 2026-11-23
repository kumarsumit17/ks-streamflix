package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Credentials
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.Library
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.NewLibrary
import com.ks.streamflix.api.ScanResult
import com.ks.streamflix.api.ServerInfo
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerApiTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    @Test fun setupSignInLibraryScanAndStream() = TestServer().use { s ->
        assertTrue(s.send(ApiV1.INFO).body().decode<ServerInfo>().setupNeeded)
        assertEquals(401, s.send(ApiV1.LIBRARIES).statusCode(), "the API needs a session")

        s.setupOwner()
        assertEquals(409, s.send(ApiV1.SETUP, "POST", ApiV1.json.encodeToString(Credentials("x", "another-pass"))).statusCode())
        assertEquals(false, s.send(ApiV1.INFO).body().decode<ServerInfo>().setupNeeded)
        assertEquals(401, s.send(ApiV1.LOGIN, "POST", ApiV1.json.encodeToString(Credentials("owner", "wrong-pass"))).statusCode())
        assertEquals(200, s.send(ApiV1.LOGIN, "POST", ApiV1.json.encodeToString(Credentials("OWNER", "secret-pass"))).statusCode())

        val media = mediaFolder(
            "The Long Road (2019).mp4" to 200_000,
            "Quiet Hills (2008)/qh.mkv" to 1_000,
            "Quiet Hills (2008)/Sample/qh-sample.mkv" to 10,
            "notes.txt" to 10,
        )
        val created = s.send(ApiV1.LIBRARIES, "POST", ApiV1.json.encodeToString(NewLibrary("Films", LibraryKind.MOVIES, listOf(media.toString()))))
        assertEquals(201, created.statusCode(), created.body())
        val library = created.body().decode<Library>()
        assertEquals(400, s.send(ApiV1.LIBRARIES, "POST", ApiV1.json.encodeToString(NewLibrary("X", LibraryKind.MOVIES, listOf("/no/such/folder")))).statusCode())

        assertEquals(ScanResult(2, 0, 0), s.send(ApiV1.libraryScan(library.id), "POST").body().decode<ScanResult>())
        assertEquals(ScanResult(0, 0, 0), s.send(ApiV1.libraryScan(library.id), "POST").body().decode<ScanResult>(), "a rescan changes nothing")

        val items = s.send("${ApiV1.ITEMS}?library=${library.id}").body().decode<ItemList>().items
        assertEquals(listOf("Quiet Hills" to 2008, "The Long Road" to 2019), items.map { it.title to it.year })
        assertTrue(items.all { it.kind == ItemKind.MOVIE })
        val film = items.single { it.title == "The Long Road" }
        assertEquals("mp4", film.container)

        val original = Files.readAllBytes(media.resolve("The Long Road (2019).mp4"))
        val full = s.bytes(ApiV1.stream(film.id))
        assertEquals(200, full.statusCode())
        assertEquals("video/mp4", full.headers().firstValue("Content-Type").get())
        assertEquals("bytes", full.headers().firstValue("Accept-Ranges").get())
        assertContentEquals(original, full.body())

        val part = s.bytes(ApiV1.stream(film.id), mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.statusCode())
        assertEquals("bytes 100-199/200000", part.headers().firstValue("Content-Range").get())
        assertContentEquals(original.copyOfRange(100, 200), part.body())

        val tail = s.bytes(ApiV1.stream(film.id), mapOf("Range" to "bytes=-10"))
        assertContentEquals(original.copyOfRange(original.size - 10, original.size), tail.body())
        assertEquals(416, s.bytes(ApiV1.stream(film.id), mapOf("Range" to "bytes=300000-")).statusCode())

        // A stream link works without the header, for this item only, and not with a changed key.
        val link = s.send(ApiV1.link(film.id), "POST").body().decode<com.ks.streamflix.api.StreamLink>()
        val saved = s.token
        s.token = null
        assertEquals(401, s.bytes(ApiV1.stream(film.id)).statusCode())
        assertEquals(206, s.bytes(link.path, mapOf("Range" to "bytes=0-9")).statusCode())
        assertEquals(401, s.bytes(link.path.replace("/${film.id}/", "/${items.first().id}/")).statusCode())
        assertEquals(401, s.bytes(link.path.dropLast(2) + "xx").statusCode())
        s.token = saved

        // A removed file: the stream is refused and a rescan drops the item.
        Files.delete(media.resolve("The Long Road (2019).mp4"))
        assertEquals(404, s.bytes(ApiV1.stream(film.id)).statusCode())
        assertEquals(ScanResult(0, 0, 1), s.send(ApiV1.libraryScan(library.id), "POST").body().decode<ScanResult>())

        assertEquals(404, s.send(ApiV1.item(99_999)).statusCode())
        assertEquals(204, s.send(ApiV1.LOGOUT, "POST").statusCode())
        assertEquals(401, s.send(ApiV1.LIBRARIES).statusCode(), "the token no longer works")
    }

    @Test fun seriesLibrary() = TestServer().use { s ->
        s.setupOwner()
        val media = mediaFolder(
            "Night Shift/Season 01/Night Shift S01E02 The Pilot.mkv" to 100,
            "Night Shift/Season 01/Night Shift S01E01.mkv" to 100,
            "Garden Tales/2x05 - Rain.mp4" to 100,
        )
        val library = s.server.run { kotlinx.coroutines.runBlocking { ensureLibrary("Series", LibraryKind.SHOWS, media.toString()) } }
        s.send(ApiV1.libraryScan(library.id), "POST")
        val items = s.send("${ApiV1.ITEMS}?library=${library.id}").body().decode<ItemList>().items
        assertEquals(
            listOf(Triple("Garden Tales", 2, 5), Triple("Night Shift", 1, 1), Triple("Night Shift", 1, 2)),
            items.map { Triple(it.series, it.season, it.episode) },
        )
    }

    @Test fun linksOutOfTheLibraryAreNotServed() = TestServer().use { s ->
        s.setupOwner()
        val outside = mediaFolder("secret.mkv" to 100)
        val media = mediaFolder("Film (2020).mkv" to 100)
        Files.createSymbolicLink(media.resolve("Linked (2021).mkv"), outside.resolve("secret.mkv"))
        val library = kotlinx.coroutines.runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
        assertEquals(1, s.send(ApiV1.libraryScan(library.id), "POST").body().decode<ScanResult>().added, "links are not followed")
    }

    @Test fun repeatedWrongPasswordsAreLimited() = TestServer().use { s ->
        s.setupOwner()
        val wrong = ApiV1.json.encodeToString(Credentials("owner", "wrong-pass"))
        repeat(5) { assertEquals(401, s.send(ApiV1.LOGIN, "POST", wrong).statusCode()) }
        assertEquals(429, s.send(ApiV1.LOGIN, "POST", ApiV1.json.encodeToString(Credentials("owner", "secret-pass"))).statusCode())
    }

    @Test fun databaseSurvivesARestart() {
        val data = Files.createTempDirectory("sf-data")
        val media = mediaFolder("Film (2020).mkv" to 100)
        TestServer(data).use { s ->
            s.setupOwner()
            val library = kotlinx.coroutines.runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
            s.send(ApiV1.libraryScan(library.id), "POST")
        }
        TestServer(data).use { s ->
            assertEquals(false, s.send(ApiV1.INFO).body().decode<ServerInfo>().setupNeeded)
            s.send(ApiV1.LOGIN, "POST", ApiV1.json.encodeToString(Credentials("owner", "secret-pass"))).also {
                s.token = it.body().decode<com.ks.streamflix.api.Session>().token
            }
            assertEquals(1, s.send(ApiV1.LIBRARIES).body().decode<com.ks.streamflix.api.LibraryList>().libraries.single().itemCount)
        }
    }
}
