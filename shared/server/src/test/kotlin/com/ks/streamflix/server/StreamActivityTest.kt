package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.domain.StreamActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamActivityTest {
    @Test fun activeWhileServingAndForTheGraceTime() = runBlocking {
        var now = 1_000_000L
        val activity = StreamActivity(graceMillis = 60_000, clock = { now })
        assertFalse(activity.isActive, "nothing streamed yet")
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val answer = async { activity.serving { inside.complete(Unit); release.await() } }
        inside.await()
        now += 10 * 60_000 // a long answer (a whole film) stays active however long it takes
        assertTrue(activity.isActive)
        release.complete(Unit)
        answer.await()
        now += 59_000
        assertTrue(activity.isActive, "a pause between a player's requests")
        now += 2_000
        assertFalse(activity.isActive, "nobody has asked for a minute")
    }

    @Test fun theStreamRouteCounts() = TestServer().use { s ->
        s.setupOwner()
        val media = mediaFolder("Film (2000).mp4" to 50_000)
        val library = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()) }
        s.send(ApiV1.libraryScan(library.id), "POST")
        val id = ApiV1.json.decodeFromString<ItemList>(s.send("${ApiV1.ITEMS}?library=${library.id}").body()).items.single().id
        assertFalse(s.server.streaming.isActive, "browsing is not streaming")
        s.bytes(ApiV1.stream(id), mapOf("Range" to "bytes=0-99"))
        assertTrue(s.server.streaming.isActive)
    }
}
