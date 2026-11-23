package com.ks.streamflix.server.details

import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemDetails
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/** What one metadata plugin knows; null fields are left to the next plugin. */
@kotlinx.serialization.Serializable
data class PartialDetails(
    val title: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    /** An age certification as the source writes it (`PG-13`, `UA`, `15`). */
    val certification: String? = null,
)

/**
 * A source of details and images for items: plugins such as `local-metadata` and `tmdb` add one.
 * The core merges them by [priority] (lower first) and serves the result; it reads no metadata itself.
 */
interface MetadataProvider {
    val id: String
    val priority: Int

    suspend fun details(item: Item, file: Path): PartialDetails?

    /** An image file for [kind], or null. */
    suspend fun image(item: Item, file: Path, kind: ImageKind): Path?
}

/** The core's merge of all metadata providers, and the resized-image cache. */
class DetailsService(private val cacheDir: Path) {
    private val providers = CopyOnWriteArrayList<MetadataProvider>()

    fun add(provider: MetadataProvider) {
        providers += provider
        providers.sortWith(compareBy { it.priority })
    }

    val hasProviders get() = providers.isNotEmpty()

    suspend fun details(item: Item, file: Path): ItemDetails {
        val parts = providers.mapNotNull { p -> runCatching { p.details(item, file) }.getOrNull()?.let { p.id to it } }
        val images = ImageKind.entries.filter { kind -> providers.any { runCatching { it.image(item, file, kind) }.getOrNull() != null } }
        return ItemDetails(
            itemId = item.id,
            title = parts.firstNotNullOfOrNull { it.second.title },
            plot = parts.firstNotNullOfOrNull { it.second.plot },
            genres = parts.firstOrNull { it.second.genres.isNotEmpty() }?.second?.genres ?: emptyList(),
            rating = parts.firstNotNullOfOrNull { it.second.rating },
            certification = parts.firstNotNullOfOrNull { it.second.certification },
            images = images,
            sources = parts.map { it.first }.distinct(),
        )
    }

    /** The [kind] image of [item] as a JPEG [width] pixels wide (snapped to a step), from the cache when it can. */
    suspend fun image(item: Item, file: Path, kind: ImageKind, width: Int): Path? {
        val source = providers.firstNotNullOfOrNull { runCatching { it.image(item, file, kind) }.getOrNull() } ?: return null
        return resized(source, snapWidth(width))
    }

    /** Widths are snapped to steps, so a few sizes are cached per image, not one per screen. */
    fun snapWidth(width: Int): Int = WIDTHS.firstOrNull { it >= width } ?: WIDTHS.last()

    private fun resized(source: Path, width: Int): Path? {
        if (!source.isRegularFile()) return null
        val key = sha("${source.toAbsolutePath()}|${source.getLastModifiedTime().toMillis()}|$width")
        val target = cacheDir.resolve("${key.take(2)}/$key.jpg")
        if (target.isRegularFile()) return target
        val image = ImageIO.read(source.toFile()) ?: return null
        val w = minOf(width, image.width)
        val h = (image.height.toLong() * w / image.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "img", ".tmp")
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(temp.toFile()).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
            writer.write(null, IIOImage(out, null, null), param)
            writer.dispose()
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val WIDTHS = listOf(160, 240, 320, 480, 640, 960, 1280, 1920)
    }
}
