package com.ks.streamflix.plugins.tmdb

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.TmdbApi
import com.ks.streamflix.api.TmdbSettings
import com.ks.streamflix.api.TmdbSettingsUpdate
import com.ks.streamflix.server.details.MetadataProvider
import com.ks.streamflix.server.details.PartialDetails
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.http.SessionPrincipal
import com.ks.streamflix.server.lang.Language
import com.ks.streamflix.server.lang.Strings
import com.ks.streamflix.server.plugin.LANG_COOKIE
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Plugin `tmdb`: details and posters from The Movie Database, with the owner's own free API key.
 * Off until the owner turns it on; then only titles and years are sent to TMDB, nothing about the
 * owner. Local files (`local-metadata`) win over it. Clients show TMDB's attribution notice.
 */
class TmdbPlugin(
    private val apiBase: String = "https://api.themoviedb.org/3",
    private val imageBase: String = "https://image.tmdb.org/t/p",
) : ServerPlugin {
    override val id = "tmdb"

    @Serializable
    private data class Settings(val enabled: Boolean = false, val apiKey: String? = null, val language: String = "en-US")

    @Serializable
    private data class Found(val details: PartialDetails? = null, val poster: String? = null, val backdrop: String? = null)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val lock = Mutex()

    override fun install(context: PluginContext) {
        val dir = context.dataDir
        Files.createDirectories(dir.resolve("cache"))
        val settingsFile = dir.resolve("settings.json")
        fun load() = runCatching { ApiV1.json.decodeFromString<Settings>(settingsFile.readText()) }.getOrNull() ?: Settings()
        fun save(s: Settings) {
            settingsFile.writeText(ApiV1.json.encodeToString(s))
            runCatching { Files.setPosixFilePermissions(settingsFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        }

        context.addMetadataProvider(object : MetadataProvider {
            override val id = "tmdb"
            override val priority = 10
            override suspend fun details(item: Item, file: Path) = lookup(item, load(), dir)?.details
            override suspend fun image(item: Item, file: Path, kind: ImageKind): Path? {
                val found = lookup(item, load(), dir) ?: return null
                val path = (if (kind == ImageKind.POSTER) found.poster else found.backdrop) ?: return null
                return download(path, if (kind == ImageKind.POSTER) "w780" else "w1280", dir)
            }
        })

        context.addAdminPage("tmdb.page.title", "/plugins/tmdb")
        val strings = context.strings
        context.routes {
            authenticate(SESSION_AUTH) {
                get(TmdbApi.SETTINGS) {
                    call.requireOwner()
                    val s = load()
                    call.respond(TmdbSettings(s.enabled, s.apiKey != null, s.language))
                }
                put(TmdbApi.SETTINGS) {
                    call.requireOwner()
                    val u = call.receive<TmdbSettingsUpdate>()
                    val old = load()
                    save(old.copy(enabled = u.enabled, apiKey = u.apiKey?.trim()?.ifEmpty { null } ?: old.apiKey, language = u.language ?: old.language))
                    val s = load()
                    call.respond(TmdbSettings(s.enabled, s.apiKey != null, s.language))
                }
            }
            authenticate(SESSION_AUTH, optional = true) {
                get("/plugins/tmdb") {
                    val user = call.principal<SessionPrincipal>()?.user ?: return@get call.respondRedirect("/login")
                    if (!user.owner) return@get call.respondText("", status = HttpStatusCode.Forbidden)
                    call.page(strings, load(), saved = call.request.queryParameters["saved"] != null)
                }
                post("/plugins/tmdb") {
                    val user = call.principal<SessionPrincipal>()?.user ?: return@post call.respondRedirect("/login")
                    val origin = call.request.headers[HttpHeaders.Origin]
                    if (!user.owner || (origin != null && origin != "null" && URI(origin).host != call.request.host())) {
                        return@post call.respondText("", status = HttpStatusCode.Forbidden)
                    }
                    val form = call.receiveParameters()
                    val old = load()
                    save(old.copy(enabled = form["enabled"] == "on", apiKey = form["key"]?.trim()?.ifEmpty { null } ?: old.apiKey, language = form["language"]?.trim()?.ifEmpty { null } ?: old.language))
                    call.respondRedirect("/plugins/tmdb?saved")
                }
            }
        }
    }

    private fun ApplicationCall.requireOwner() {
        if (principal<SessionPrincipal>()?.user?.owner != true) throw DomainException("auth.error.ownerOnly", DomainException.Kind.FORBIDDEN)
    }

    // ---- Looking things up -------------------------------------------------------------------------

    /** What TMDB says about [item], from the cache or from TMDB (one lookup at a time). */
    private suspend fun lookup(item: Item, settings: Settings, dir: Path): Found? {
        val key = settings.apiKey
        if (!settings.enabled || key == null) return null
        val query = when (item.kind) {
            ItemKind.MOVIE -> "movie|${item.title}|${item.year}"
            ItemKind.EPISODE -> "episode|${item.series}|${item.season}|${item.episode}"
        } + "|${settings.language}"
        val cache = dir.resolve("cache/${sha(query)}.json")
        if (cache.exists()) return runCatching { ApiV1.json.decodeFromString<Found>(cache.readText()) }.getOrNull()
        return lock.withLock {
            if (cache.exists()) return@withLock runCatching { ApiV1.json.decodeFromString<Found>(cache.readText()) }.getOrNull()
            val found = runCatching { fetch(item, key, settings.language) }.getOrNull() ?: return@withLock null
            cache.writeText(ApiV1.json.encodeToString(found))
            found
        }
    }

    private suspend fun fetch(item: Item, key: String, language: String): Found = withContext(Dispatchers.IO) {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        when (item.kind) {
            ItemKind.MOVIE -> {
                val year = item.year?.let { "&year=$it" } ?: ""
                val hit = get("/search/movie?query=${enc(item.title)}$year&language=$language", key)?.firstResult()
                    ?: return@withContext Found()
                Found(
                    PartialDetails(hit.str("title"), hit.str("overview")?.ifEmpty { null }, emptyList(), hit.num("vote_average")?.takeIf { it > 0 }),
                    hit.str("poster_path"), hit.str("backdrop_path"),
                )
            }
            ItemKind.EPISODE -> {
                val show = get("/search/tv?query=${enc(item.series ?: item.title)}&language=$language", key)?.firstResult()
                    ?: return@withContext Found()
                val showId = (show["id"] as? JsonPrimitive)?.longOrNull ?: return@withContext Found()
                val ep = if (item.season != null && item.episode != null) get("/tv/$showId/season/${item.season}/episode/${item.episode}?language=$language", key) else null
                Found(
                    PartialDetails(ep?.str("name"), ep?.str("overview")?.ifEmpty { null } ?: show.str("overview")?.ifEmpty { null }, emptyList(), ep?.num("vote_average")?.takeIf { it > 0 }),
                    show.str("poster_path"), ep?.str("still_path") ?: show.str("backdrop_path"),
                )
            }
        }
    }

    /** A GET to TMDB. A v4 read token (long, with dots) goes in the header; a v3 key in the query. */
    private fun get(path: String, key: String): JsonObject? {
        val bearer = key.length > 40 && '.' in key
        val url = apiBase + path + if (bearer) "" else "&api_key=${URLEncoder.encode(key, Charsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }.GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return ApiV1.json.parseToJsonElement(response.body()).jsonObject
    }

    private fun download(path: String, size: String, dir: Path): Path? {
        val file = dir.resolve("cache/${sha("$size$path")}.${path.substringAfterLast('.', "jpg")}")
        if (file.isRegularFile()) return file
        val response = runCatching {
            http.send(HttpRequest.newBuilder(URI("$imageBase/$size$path")).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }.getOrNull() ?: return null
        if (response.statusCode() != 200) return null
        Files.write(file, response.body())
        return file
    }

    private fun JsonObject.firstResult() = (this["results"] as? JsonArray)?.firstOrNull() as? JsonObject
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- The owner's settings page -----------------------------------------------------------------

    private suspend fun ApplicationCall.page(strings: Strings, s: Settings, saved: Boolean) {
        val lang = Language.resolve(request.cookies[LANG_COOKIE], request.headers[HttpHeaders.AcceptLanguage])
        fun t(key: String) = esc(strings.get(lang, key))
        val html = """<!doctype html><html lang=${lang.code}><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>${t("tmdb.page.title")} · KS StreamFlix</title><style>
:root{color-scheme:light dark;--b:#e4e4e7;--m:#71717a}@media(prefers-color-scheme:dark){:root{--b:#27272a;--m:#a1a1aa}}
body{font:15px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI","Noto Sans Devanagari",sans-serif;max-width:560px;margin:2rem auto;padding:0 1rem}
form{display:grid;gap:.9rem}label{display:grid;gap:.35rem;font-weight:500}input[type=text],input[type=password]{height:2.25rem;padding:0 .75rem;border:1px solid var(--b);border-radius:8px;background:transparent;color:inherit;font:inherit}
button{height:2.25rem;border:0;border-radius:8px;background:#18181b;color:#fafafa;font:inherit;font-weight:500;padding:0 1rem;justify-self:start}
@media(prefers-color-scheme:dark){button{background:#fafafa;color:#18181b}}.m{color:var(--m);font-size:13px}.ok{color:#16a34a}.row{display:flex;gap:.5rem;align-items:center;font-weight:500}
</style></head><body><p><a href="/">← ${t("common.back")}</a></p><h1>${t("tmdb.page.title")}</h1>
<p class=m>${t("tmdb.page.intro")}</p>${if (saved) "<p class=ok role=status>${t("common.saved")}</p>" else ""}
<form method=post action="/plugins/tmdb">
<label class=row><input type=checkbox name=enabled ${if (s.enabled) "checked" else ""}> ${t("tmdb.page.enabled")}</label>
<label>${t("tmdb.page.key")}<input type=password name=key autocomplete=off placeholder="${if (s.apiKey != null) t("tmdb.page.keySaved") else ""}"></label>
<label>${t("tmdb.page.language")}<input type=text name=language value="${esc(s.language)}"></label>
<button>${t("common.save")}</button></form>
<p class=m>${t("details.attribution.tmdb")}</p></body></html>"""
        response.headers.append("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'")
        respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
