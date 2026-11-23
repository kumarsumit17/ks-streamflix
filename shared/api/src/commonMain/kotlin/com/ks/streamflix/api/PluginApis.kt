package com.ks.streamflix.api

import kotlinx.serialization.Serializable

/** Plugin `watch-state`: progress, watched, continue watching (per user). */
object WatchStateApi {
    const val BASE = "${ApiV1.BASE}/plugins/watch-state"
    const val CONTINUE = "$BASE/continue"
    fun progress(libraryId: Long) = "$BASE/progress?library=$libraryId"
    fun item(id: Long) = "$BASE/items/$id"
    fun watched(id: Long) = "$BASE/items/$id/watched"

    /** At or past this share of the duration an item counts as watched. */
    const val WATCHED_SHARE = 0.9
}

@Serializable
data class WatchProgress(val itemId: Long, val positionMs: Long, val durationMs: Long, val watched: Boolean, val updatedAt: Long)

@Serializable
data class ProgressUpdate(val positionMs: Long, val durationMs: Long)

@Serializable
data class WatchedUpdate(val watched: Boolean)

@Serializable
data class ProgressList(val progress: List<WatchProgress>)

@Serializable
data class ContinueItem(val item: Item, val progress: WatchProgress)

@Serializable
data class ContinueList(val items: List<ContinueItem>)

/** Plugin `discovery`: a client sends [QUESTION] as a UDP broadcast to [PORT]; servers answer with a [DiscoveryAnswer] in JSON. */
object DiscoveryApi {
    const val PORT = 8742
    const val QUESTION = "KS-STREAMFLIX-WHO-IS-THERE/1"
}

@Serializable
data class DiscoveryAnswer(val name: String, val port: Int, val version: String, val apiVersion: Int = ApiV1.VERSION)
