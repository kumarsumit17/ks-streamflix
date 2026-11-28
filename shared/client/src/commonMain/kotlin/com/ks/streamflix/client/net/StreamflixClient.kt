package com.ks.streamflix.client.net

import com.ks.streamflix.api.ApiError
import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ContinueList
import com.ks.streamflix.api.Credentials
import com.ks.streamflix.api.PlaybackDecision
import com.ks.streamflix.api.PlaybackRequest
import com.ks.streamflix.api.TranscodeApi
import com.ks.streamflix.api.DetailsApi
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.ItemDetails
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.MediaInfoApi
import com.ks.streamflix.api.SubtitleList
import com.ks.streamflix.api.SubtitleTrack
import com.ks.streamflix.api.SubtitlesApi
import com.ks.streamflix.api.DiscoveryApi
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.Library
import com.ks.streamflix.api.LibraryList
import com.ks.streamflix.api.ProgressList
import com.ks.streamflix.api.ProgressUpdate
import com.ks.streamflix.api.ServerInfo
import com.ks.streamflix.api.Session
import com.ks.streamflix.api.StreamLink
import com.ks.streamflix.api.WatchProgress
import com.ks.streamflix.api.WatchStateApi
import com.ks.streamflix.api.WatchedUpdate
import kotlinx.serialization.encodeToString

/** One HTTP exchange; each platform provides it ([HttpTransport]). */
class HttpResponse(val status: Int, val bytes: ByteArray) {
    val body: String get() = bytes.decodeToString()
}

/** The platform's HTTP, as small as the client needs. Blocking calls are moved off the main thread by the implementation. */
interface HttpTransport {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** An answer the server refused. [code] is a key of the server's language files, or `client.error.*`. */
class ApiException(val status: Int, val code: String) : Exception("$status $code")

/** A server address, normalised: `http://host:port` with no slash at the end. */
fun normaliseServer(input: String, defaultPort: Int = DiscoveryApi.PORT): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    val hostPart = s.substringAfter("://").substringBefore('/')
    if (hostPart.isEmpty()) return null
    if (':' !in hostPart.substringAfterLast(']')) s = s.replaceFirst(hostPart, "$hostPart:$defaultPort")
    return s
}

/** The StreamFlix API, for the apps. */
class StreamflixClient(private val transport: HttpTransport, val server: String, var token: String? = null) {

    suspend fun info(): ServerInfo = get(ApiV1.INFO)
    suspend fun signIn(name: String, password: String): Session =
        send<Session>("POST", ApiV1.LOGIN, ApiV1.json.encodeToString(Credentials(name, password))).also { token = it.token }
    suspend fun signOut() { runCatching { raw("POST", ApiV1.LOGOUT, null) } ; token = null }
    suspend fun libraries(): List<Library> = get<LibraryList>(ApiV1.LIBRARIES).libraries
    suspend fun items(libraryId: Long): List<Item> = get<ItemList>("${ApiV1.ITEMS}?library=$libraryId").items
    suspend fun item(id: Long): Item = get(ApiV1.item(id))
    suspend fun streamLink(id: Long): String = server + send<StreamLink>("POST", ApiV1.link(id), null).path

    /** The stream URL for a player that sends [authHeaders]. */
    fun streamUrl(id: Long) = server + ApiV1.stream(id)
    val authHeaders: Map<String, String> get() = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    // Plugin `watch-state` on the server. A server without it answers 404, which callers treat as "no data".
    suspend fun continueWatching() = get<ContinueList>(WatchStateApi.CONTINUE).items
    suspend fun progress(libraryId: Long) = get<ProgressList>(WatchStateApi.progress(libraryId)).progress
    suspend fun saveProgress(id: Long, positionMs: Long, durationMs: Long): WatchProgress =
        send("PUT", WatchStateApi.item(id), ApiV1.json.encodeToString(ProgressUpdate(positionMs, durationMs)))
    suspend fun setWatched(id: Long, watched: Boolean): WatchProgress =
        send("POST", WatchStateApi.watched(id), ApiV1.json.encodeToString(WatchedUpdate(watched)))

    // Details and images (the core merges what metadata plugins know).
    suspend fun details(id: Long): ItemDetails = get(DetailsApi.details(id))
    /** The image bytes, or null when the item has none. */
    suspend fun image(id: Long, kind: ImageKind, width: Int): ByteArray? =
        runCatching { bytes("GET", DetailsApi.image(id, kind, width)) }.getOrNull()

    // Plugins `media-info` and `subtitles` on the server.
    suspend fun mediaInfo(id: Long): MediaInfo = get(MediaInfoApi.item(id))
    suspend fun subtitles(id: Long): List<SubtitleTrack> = get<SubtitleList>(SubtitlesApi.list(id)).tracks
    fun url(path: String) = server + path

    // Plugin `transcode` on the server.
    suspend fun playback(id: Long, request: PlaybackRequest): PlaybackDecision =
        send("POST", TranscodeApi.playback(id), ApiV1.json.encodeToString(request))
    suspend fun stopSession(sessionId: String) { runCatching { raw("DELETE", TranscodeApi.session(sessionId), null) } }

    private suspend inline fun <reified T> get(path: String): T = send("GET", path, null)

    private suspend inline fun <reified T> send(method: String, path: String, body: String?): T =
        ApiV1.json.decodeFromString(raw(method, path, body))

    private suspend fun raw(method: String, path: String, body: String?): String = bytes(method, path, body).decodeToString()

    private suspend fun bytes(method: String, path: String, body: String? = null): ByteArray {
        val headers = buildMap {
            putAll(authHeaders)
            if (body != null) put("Content-Type", "application/json")
        }
        val response = try {
            transport.request(method, server + path, headers, body)
        } catch (e: Exception) {
            throw ApiException(0, "client.error.unreachable")
        }
        if (response.status !in 200..299) {
            val code = runCatching { ApiV1.json.decodeFromString<ApiError>(response.body).code }.getOrNull() ?: "common.error.generic"
            throw ApiException(response.status, code)
        }
        return response.bytes
    }
}
