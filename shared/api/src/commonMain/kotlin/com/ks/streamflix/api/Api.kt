package com.ks.streamflix.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version 1 of the StreamFlix API. Every path starts with [ApiV1.BASE]. */
object ApiV1 {
    const val VERSION = 1
    const val BASE = "/api/v1"
    const val INFO = "$BASE/info"
    const val SETUP = "$BASE/setup"
    const val LOGIN = "$BASE/login"
    const val LOGOUT = "$BASE/logout"
    const val LIBRARIES = "$BASE/libraries"
    const val ITEMS = "$BASE/items"

    fun libraryScan(id: Long) = "$LIBRARIES/$id/scan"
    fun item(id: Long) = "$ITEMS/$id"
    fun stream(id: Long) = "$ITEMS/$id/stream"
    fun link(id: Long) = "$ITEMS/$id/link"

    /** The JSON settings both sides use: unknown fields are ignored, so a newer server stays readable. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val apiVersion: Int = ApiV1.VERSION,
    /** True until the owner account has been created. */
    val setupNeeded: Boolean,
)

@Serializable
data class Credentials(val name: String, val password: String)

@Serializable
data class Session(val token: String, val user: User)

@Serializable
data class User(val id: Long, val name: String, val owner: Boolean)

@Serializable
enum class LibraryKind {
    @SerialName("movies") MOVIES,
    @SerialName("shows") SHOWS,
}

@Serializable
data class NewLibrary(val name: String, val kind: LibraryKind, val folders: List<String>)

@Serializable
data class Library(
    val id: Long,
    val name: String,
    val kind: LibraryKind,
    val folders: List<String>,
    val itemCount: Int,
)

@Serializable
data class ScanResult(val added: Int, val updated: Int, val removed: Int, val missingFolders: List<String> = emptyList())

@Serializable
enum class ItemKind {
    @SerialName("movie") MOVIE,
    @SerialName("episode") EPISODE,
}

@Serializable
data class Item(
    val id: Long,
    val libraryId: Long,
    val kind: ItemKind,
    val title: String,
    val year: Int? = null,
    /** For an episode: the series it belongs to. */
    val series: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val size: Long,
    /** The file's extension in lower case (`mkv`, `mp4`), for the client's direct-play decision. */
    val container: String,
)

@Serializable
data class ItemList(val items: List<Item>)

@Serializable
data class LibraryList(val libraries: List<Library>)

/**
 * A stream link a player can open without the sign-in header: [path] (relative to the server,
 * with its `key`) works for this one item until [expiresAt].
 */
@Serializable
data class StreamLink(val path: String, val expiresAt: Long)

/** The body of every error response. [code] is a key of the server's language files. */
@Serializable
data class ApiError(val code: String)

// ---- Details and images (core extension point; the data comes from metadata plugins) -------------

object DetailsApi {
    fun details(id: Long) = "${ApiV1.ITEMS}/$id/details"

    /** A [kind] image resized on the server to [width] pixels (rounded up to a fixed step). */
    fun image(id: Long, kind: ImageKind, width: Int) = "${ApiV1.ITEMS}/$id/image/${kind.name.lowercase()}?width=$width"
}

@Serializable
enum class ImageKind {
    @SerialName("poster") POSTER,
    @SerialName("backdrop") BACKDROP,
}

/** What metadata plugins know about an item, merged by the core (the first plugin with a value wins). */
@Serializable
data class ItemDetails(
    val itemId: Long,
    val title: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    /** The image kinds that exist for this item. */
    val images: List<ImageKind> = emptyList(),
    /** Where the data came from (plugin ids), for the attribution some sources need. */
    val sources: List<String> = emptyList(),
)
