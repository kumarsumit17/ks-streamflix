package com.ks.streamflix.plugins.localmetadata

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DetailsApi
import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.ItemDetails
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.TestServer
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMetadataPluginTest {
    private inline fun <reified T> String.decode(): T = ApiV1.json.decodeFromString(this)

    private fun jpeg(path: Path, w: Int, h: Int) {
        Files.createDirectories(path.parent)
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", path.toFile())
    }

    @Test fun postersNfoAndResizing() {
        val media = Files.createTempDirectory("sf-media")
        val own = media.resolve("Quiet Hills (2008)")
        Files.createDirectories(own)
        Files.write(own.resolve("qh.mkv"), ByteArray(100))
        jpeg(own.resolve("poster.jpg"), 1000, 1500)
        Files.writeString(own.resolve("movie.nfo"), """<?xml version="1.0"?><movie><title>Quiet Hills</title><plot>A quiet film.</plot><genre>Drama / Family</genre><rating>7.5</rating></movie>""")
        Files.write(media.resolve("Loose (2010).mkv"), ByteArray(100))
        jpeg(media.resolve("poster.jpg"), 300, 450) // the library folder's own poster belongs to no film
        // An .nfo that tries to read a file through an external entity is refused.
        Files.write(media.resolve("Sneaky (2011).mkv"), ByteArray(100))
        Files.writeString(media.resolve("Sneaky (2011).nfo"), """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/hosts">]><movie><plot>&x;</plot></movie>""")

        TestServer(plugins = listOf(LocalMetadataPlugin())).use { s ->
            s.setupOwner()
            val lib = runBlocking { s.server.ensureLibrary("Films", LibraryKind.MOVIES, media.toString()).also { s.server.libraries.scan(it.id) } }
            val items = s.send("${ApiV1.ITEMS}?library=${lib.id}").body().decode<ItemList>().items.associateBy { it.title }

            val qh = s.send(DetailsApi.details(items.getValue("Quiet Hills").id)).body().decode<ItemDetails>()
            assertEquals("A quiet film.", qh.plot)
            assertEquals(listOf("Drama", "Family"), qh.genres)
            assertEquals(7.5, qh.rating)
            assertEquals(listOf(ImageKind.POSTER), qh.images)
            assertEquals(listOf("local-metadata"), qh.sources)

            val image = s.bytes(DetailsApi.image(items.getValue("Quiet Hills").id, ImageKind.POSTER, 300))
            assertEquals("image/jpeg", image.headers().firstValue("Content-Type").get())
            val decoded = ImageIO.read(ByteArrayInputStream(image.body()))
            assertEquals(320, decoded.width, "300 is snapped up to the 320 step")
            assertEquals(480, decoded.height)

            val loose = s.send(DetailsApi.details(items.getValue("Loose").id)).body().decode<ItemDetails>()
            assertTrue(loose.images.isEmpty())
            assertEquals(404, s.bytes(DetailsApi.image(items.getValue("Loose").id, ImageKind.POSTER, 300)).statusCode())

            assertNull(s.send(DetailsApi.details(items.getValue("Sneaky").id)).body().decode<ItemDetails>().plot)
        }
    }
}
