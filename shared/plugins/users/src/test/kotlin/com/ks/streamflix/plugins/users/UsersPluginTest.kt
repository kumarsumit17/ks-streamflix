package com.ks.streamflix.plugins.users

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Credentials
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.LibraryList
import com.ks.streamflix.api.NewUser
import com.ks.streamflix.api.PasswordChange
import com.ks.streamflix.api.Session
import com.ks.streamflix.api.UserList
import com.ks.streamflix.api.UserSettings
import com.ks.streamflix.api.UserUpdate
import com.ks.streamflix.api.UsersApi
import com.ks.streamflix.plugins.localmetadata.LocalMetadataPlugin
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsersPluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)
    private inline fun <reified T> T.json(): String = ApiV1.json.encodeToString(this)

    private fun film(root: Path, name: String, mpaa: String?) {
        val dir = Files.createDirectories(root.resolve(name))
        Files.write(dir.resolve("film.mp4"), ByteArray(100))
        mpaa?.let { Files.writeString(dir.resolve("movie.nfo"), "<movie><mpaa>$it</mpaa></movie>") }
    }

    @Test fun certifications() {
        assertEquals(0, Certifications.age("G"))
        assertEquals(12, Certifications.age("UA"))
        assertEquals(14, Certifications.age("Rated PG-13"))
        assertEquals(18, Certifications.age("A"))
        assertEquals(16, Certifications.age("16+"))
        assertNull(Certifications.age("Unknown"))
    }

    @Test fun librariesAgeLimitsPasswordsAndRemoval() {
        val films = Files.createTempDirectory("films").also {
            film(it, "Cartoon (2010)", "G")
            film(it, "Thriller (2011)", "Rated R")
            film(it, "Unrated (2012)", null)
        }
        val series = com.ks.streamflix.server.mediaFolder("Show/Show S01E01.mkv" to 10)
        TestServer(plugins = listOf(LocalMetadataPlugin(), UsersPlugin())).use { s ->
            s.setupOwner()
            val (filmLib, seriesLib) = runBlocking {
                listOf(s.server.ensureLibrary("Films", LibraryKind.MOVIES, films.toString()), s.server.ensureLibrary("Series", LibraryKind.SHOWS, series.toString()))
                    .onEach { s.server.libraries.scan(it.id) }
            }
            val ownerToken = s.token
            val all = s.send("${ApiV1.ITEMS}?library=${filmLib.id}").body().decode<ItemList>().items.associateBy { it.title }

            val kid = s.send(UsersApi.BASE, "POST", NewUser("Kid", "kid-pass-123", listOf(filmLib.id), maxAge = 10).json())
            assertEquals(201, kid.statusCode(), kid.body())
            val kidId = kid.body().decode<UserSettings>().id
            assertEquals(listOf("owner", "Kid"), s.send(UsersApi.BASE).body().decode<UserList>().users.map { it.name })

            // The kid's view: one library, no R film, unrated allowed for now.
            s.token = s.send(ApiV1.LOGIN, "POST", Credentials("Kid", "kid-pass-123").json()).body().decode<Session>().token
            assertEquals(listOf("Films"), s.send(ApiV1.LIBRARIES).body().decode<LibraryList>().libraries.map { it.name })
            assertEquals(listOf("Cartoon", "Unrated"), s.send("${ApiV1.ITEMS}?library=${filmLib.id}").body().decode<ItemList>().items.map { it.title })
            assertEquals(404, s.send("${ApiV1.ITEMS}?library=${seriesLib.id}").statusCode())
            val thriller = all.getValue("Thriller").id
            assertEquals(404, s.send(ApiV1.item(thriller)).statusCode())
            assertEquals(404, s.bytes(ApiV1.stream(thriller)).statusCode())
            assertEquals(404, s.send(ApiV1.link(thriller), "POST").statusCode())
            assertEquals(403, s.send(UsersApi.BASE).statusCode(), "only the owner manages users")
            val kidToken = s.token

            // Hiding unrated videos too.
            s.token = ownerToken
            s.send(UsersApi.user(kidId), "PUT", UserUpdate(listOf(filmLib.id), 10, blockUnrated = true).json())
            s.token = kidToken
            assertEquals(listOf("Cartoon"), s.send("${ApiV1.ITEMS}?library=${filmLib.id}").body().decode<ItemList>().items.map { it.title })

            // Changing one's own password ends one's sessions.
            assertEquals(403, s.send(UsersApi.ME_PASSWORD, "PUT", PasswordChange("wrong-one", "new-pass-456").json()).statusCode())
            assertEquals(204, s.send(UsersApi.ME_PASSWORD, "PUT", PasswordChange("kid-pass-123", "new-pass-456").json()).statusCode())
            assertEquals(401, s.send(ApiV1.LIBRARIES).statusCode())

            // Removing the user.
            s.token = ownerToken
            assertEquals(204, s.send(UsersApi.user(kidId), "DELETE").statusCode())
            assertEquals(401, s.send(ApiV1.LOGIN, "POST", Credentials("Kid", "new-pass-456").json()).statusCode())
            assertEquals(400, s.send(UsersApi.user(s.send(UsersApi.BASE).body().decode<UserList>().users.single().id), "DELETE").statusCode(), "the owner stays")
        }
    }
}
