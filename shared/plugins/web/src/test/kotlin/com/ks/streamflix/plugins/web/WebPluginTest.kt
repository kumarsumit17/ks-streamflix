package com.ks.streamflix.plugins.web

import com.ks.streamflix.server.ServerConfig
import com.ks.streamflix.server.StreamflixServer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPluginTest {
    private val server = StreamflixServer(
        ServerConfig(Files.createTempDirectory("sf-web"), port = 0, host = "127.0.0.1", name = "test", passwordIterations = 1_000),
        listOf(WebPlugin()),
    )
    private val base = "http://127.0.0.1:${server.start()}"
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private var cookie = ""

    private fun get(path: String, lang: String = "en") = http.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Accept-Language", lang)
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, form: Map<String, String>, origin: String? = null) = http.send(
        HttpRequest.newBuilder(URI("$base$path"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .apply { origin?.let { header("Origin", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun HttpResponse<String>.location() = headers().firstValue("Location").orElse("")

    @Test fun setupAddLibraryBrowseAndWatch() = server.use {
        assertEquals("/setup", get("/").location())
        val setup = get("/setup", lang = "hi-IN,en;q=0.5")
        assertContains(setup.body(), "मालिक का अकाउंट बनाएं", message = "the browser's language is followed")
        assertContains(setup.body(), "<html lang=hi>")

        val created = post("/setup", mapOf("name" to "owner", "password" to "secret-pass"))
        assertEquals("/", created.location())
        val setCookie = created.headers().firstValue("Set-Cookie").get()
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie, setCookie)
        cookie = setCookie.substringBefore(';')

        val media = Files.createTempDirectory("sf-media")
        Files.write(media.resolve("The Long Road (2019).mp4"), ByteArray(1000))
        assertEquals(403, post("/libraries", mapOf("name" to "x"), origin = "http://evil.example").statusCode(), "posts from other sites are refused")
        val added = post("/libraries", mapOf("name" to "Films <b>", "kind" to "movies", "folder" to media.toString()))
        assertContains(added.body(), "Scan finished: 1 added, 0 updated, 0 removed.")
        assertContains(added.body(), "Films &lt;b&gt;", message = "names are escaped")
        assertFalse("Films <b>" in added.body())

        assertContains(added.body(), "<option value=shows>Series</option>", message = "the add form renders cleanly")
        val library = Regex("""href="/library/(\d+)"""").find(added.body())!!.groupValues[1]
        val list = get("/library/$library")
        assertContains(list.body(), "The Long Road (2019)")
        val item = Regex("""href="/watch/(\d+)"""").find(list.body())!!.groupValues[1]
        assertContains(get("/watch/$item").body(), """src="/api/v1/items/$item/stream"""")

        val hindi = get("/lang?set=hi&back=/library/$library")
        assertEquals("/library/$library", hindi.location())
        cookie += "; " + hindi.headers().firstValue("Set-Cookie").get().substringBefore(';')
        assertContains(get("/", lang = "en").body(), "लाइब्रेरी", message = "the saved choice wins over the browser")
        assertEquals("/", get("/lang?set=hi&back=//evil.example").location(), "no redirect to another site")

        assertEquals("/login", post("/logout", emptyMap()).location())
        cookie = ""
        assertEquals("/login", get("/").location())
        assertEquals(401, post("/login", mapOf("name" to "owner", "password" to "nope-nope")).statusCode())
    }
}
