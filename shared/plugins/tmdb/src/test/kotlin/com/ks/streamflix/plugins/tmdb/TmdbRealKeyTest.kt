package com.ks.streamflix.plugins.tmdb

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DetailsApi
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.ItemDetails
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.TmdbApi
import com.ks.streamflix.api.TmdbSettingsUpdate
import com.ks.streamflix.server.TestServer
import com.ks.streamflix.server.mediaFolder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the real TMDB, with the owner's key in `STREAMFLIX_TMDB_KEY` (a v3 key or a v4 read
 * token). Skipped without it, so CI and other machines never need a key. Run:
 * `STREAMFLIX_TMDB_KEY=… ./gradlew :shared:plugins:tmdb:test --tests '*RealKey*'`
 */
class TmdbRealKeyTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    @Test fun aFilmAndAnEpisodeFromTheRealTmdb() {
        val key = System.getenv("STREAMFLIX_TMDB_KEY")?.takeIf { it.isNotBlank() } ?: return
        TestServer(plugins = listOf(TmdbPlugin())).use { s ->
            s.setupOwner()
            s.send(TmdbApi.SETTINGS, "PUT", ApiV1.json.encodeToString(TmdbSettingsUpdate(true, key)))
            val films = mediaFolder("The Matrix (1999).mkv" to 10)
            val shows = mediaFolder("Breaking Bad/Season 01/Breaking Bad S01E01.mkv" to 10)
            val (filmLib, showLib) = runBlocking {
                listOf(s.server.ensureLibrary("Films", LibraryKind.MOVIES, films.toString()), s.server.ensureLibrary("Series", LibraryKind.SHOWS, shows.toString()))
                    .onEach { s.server.libraries.scan(it.id) }
            }
            val film = s.send("${ApiV1.ITEMS}?library=${filmLib.id}").body().decode<ItemList>().items.single()
            val filmDetails = s.send(DetailsApi.details(film.id)).body().decode<ItemDetails>()
            assertEquals("The Matrix", filmDetails.title)
            assertTrue(!filmDetails.plot.isNullOrBlank() && ImageKind.POSTER in filmDetails.images, filmDetails.toString())
            assertEquals(200, s.bytes(DetailsApi.image(film.id, ImageKind.POSTER, 320)).statusCode())

            val episode = s.send("${ApiV1.ITEMS}?library=${showLib.id}").body().decode<ItemList>().items.single()
            val epDetails = s.send(DetailsApi.details(episode.id)).body().decode<ItemDetails>()
            assertTrue(!epDetails.title.isNullOrBlank() && !epDetails.plot.isNullOrBlank(), epDetails.toString())
            println("TMDB: film '${filmDetails.title}', episode '${epDetails.title}'")
        }
    }
}
