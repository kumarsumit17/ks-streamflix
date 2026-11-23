package com.ks.streamflix.plugins.tmdb

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DetailsApi
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.ItemDetails
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.TmdbApi
import com.ks.streamflix.api.TmdbSettings
import com.ks.streamflix.api.TmdbSettingsUpdate
import com.ks.streamflix.server.TestServer
import com.ks.streamflix.server.mediaFolder
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbPluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    @Test fun offUntilTurnedOnThenFillsDetailsAndPosters() {
        val asked = CopyOnWriteArrayList<String>()
        val poster = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(500, 750, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                asked += ex.requestURI.toString()
                val (status, body, type) = when {
                    ex.requestURI.path == "/3/search/movie" -> Triple(200, """{"results":[{"id":1,"title":"The Long Road","overview":"From TMDB.","vote_average":8.1,"poster_path":"/p.jpg"}]}""".toByteArray(), "application/json")
                    ex.requestURI.path == "/t/p/w780/p.jpg" -> Triple(200, poster, "image/jpeg")
                    else -> Triple(404, ByteArray(0), "text/plain")
                }
                ex.responseHeaders.add("Content-Type", type)
                ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val base = "http://127.0.0.1:${fake.address.port}"
        try {
            TestServer(plugins = listOf(TmdbPlugin("$base/3", "$base/t/p"))).use { s ->
                s.setupOwner()
                val media = mediaFolder("The Long Road (2019).mp4" to 100)
                val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
                val item = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.single()

                assertEquals(null, s.send(DetailsApi.details(item.id)).body().decode<ItemDetails>().plot)
                assertTrue(asked.isEmpty(), "nothing is sent to TMDB while the plugin is off")

                val saved = s.send(TmdbApi.SETTINGS, "PUT", ApiV1.json.encodeToString(TmdbSettingsUpdate(true, "test-key-123"))).body().decode<TmdbSettings>()
                assertEquals(TmdbSettings(true, true, "en-US"), saved)
                assertFalse("test-key-123" in s.send(TmdbApi.SETTINGS).body(), "the key is never sent back")

                val details = s.send(DetailsApi.details(item.id)).body().decode<ItemDetails>()
                assertEquals("From TMDB.", details.plot)
                assertEquals(8.1, details.rating)
                assertEquals(listOf(ImageKind.POSTER), details.images)
                assertEquals(listOf("tmdb"), details.sources)
                assertTrue(asked.first().contains("query=The+Long+Road") && asked.first().contains("year=2019") && asked.first().contains("api_key=test-key-123"))
                assertEquals(200, s.bytes(DetailsApi.image(item.id, ImageKind.POSTER, 240)).statusCode())

                val before = asked.size
                s.send(DetailsApi.details(item.id))
                assertEquals(before, asked.size, "answers are cached")

                // The settings page is for the owner; others are sent to sign in.
                s.token = null
                assertEquals(302, s.send("/plugins/tmdb").statusCode())
            }
        } finally {
            fake.stop(0)
        }
    }
}
