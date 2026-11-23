package com.ks.streamflix.plugins.localmetadata

import com.ks.streamflix.api.ImageKind
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.server.details.MetadataProvider
import com.ks.streamflix.server.details.PartialDetails
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Finds the files next to a video: images and Kodi-style `.nfo` details. */
object LocalFiles {
    private val imageExt = listOf("jpg", "jpeg", "png")

    /**
     * The folders from the video's own folder up to, but not including, its library folder: a
     * film's own folder, or an episode's season and series folders. A library folder's own images
     * belong to no single video.
     */
    fun ownFolders(video: Path, libraryRoots: List<Path>): List<Path> {
        val root = libraryRoots.firstOrNull { video.startsWith(it) } ?: return emptyList()
        return generateSequence(video.parent) { it.parent }.takeWhile { it != root && it.startsWith(root) }.toList()
    }

    private fun firstImage(dir: Path, names: List<String>): Path? =
        names.asSequence().flatMap { n -> imageExt.asSequence().map { dir.resolve("$n.$it") } }.firstOrNull { it.isRegularFile() }

    fun image(video: Path, roots: List<Path>, kind: ImageKind): Path? {
        val stem = video.name.substringBeforeLast('.')
        val dir = video.parent
        val own = when (kind) {
            ImageKind.POSTER -> listOf("$stem-poster", stem, "$stem-thumb")
            ImageKind.BACKDROP -> listOf("$stem-fanart", "$stem-backdrop")
        }
        firstImage(dir, own)?.let { return it }
        val shared = when (kind) {
            ImageKind.POSTER -> listOf("poster", "folder", "cover")
            ImageKind.BACKDROP -> listOf("fanart", "backdrop", "background")
        }
        return ownFolders(video, roots).firstNotNullOfOrNull { firstImage(it, shared) }
    }

    /** The `.nfo` for a video: `<name>.nfo`, or `movie.nfo` in a film's own folder. */
    fun nfo(video: Path, roots: List<Path>, kind: ItemKind): Path? {
        val stem = video.name.substringBeforeLast('.')
        video.parent.resolve("$stem.nfo").takeIf { it.isRegularFile() }?.let { return it }
        if (kind == ItemKind.MOVIE && ownFolders(video, roots).isNotEmpty()) {
            video.parent.resolve("movie.nfo").takeIf { it.isRegularFile() }?.let { return it }
        }
        return null
    }

    /** Reads title, plot, genres and rating from a Kodi `.nfo` (XML). External entities are refused. */
    fun parseNfo(file: Path): PartialDetails? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val root = factory.newDocumentBuilder().parse(file.toFile()).documentElement
        fun text(tag: String) = root.getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent?.trim()?.ifEmpty { null } else null }
        val genres = (0 until root.getElementsByTagName("genre").length)
            .mapNotNull { root.getElementsByTagName("genre").item(it).textContent?.trim()?.ifEmpty { null } }
            .flatMap { it.split('/').map(String::trim) }.filter { it.isNotEmpty() }.distinct()
        val rating = text("rating")?.toDoubleOrNull()
            ?: (root.getElementsByTagName("ratings").item(0) as? Element)?.getElementsByTagName("value")?.item(0)?.textContent?.trim()?.toDoubleOrNull()
        // Kodi writes `<mpaa>Rated PG-13</mpaa>` or `<mpaa>IN:UA</mpaa>`; the certification is the last word.
        val certification = (text("mpaa") ?: text("certification"))?.substringAfterLast(':')?.removePrefix("Rated ")?.trim()?.ifEmpty { null }
        PartialDetails(text("title"), text("plot") ?: text("outline"), genres, rating, certification)
    }.getOrNull()
}

/** Plugin `local-metadata`: posters, backdrops and `.nfo` details from the files next to each video. */
class LocalMetadataPlugin : ServerPlugin {
    override val id = "local-metadata"

    override fun install(context: PluginContext) {
        val libraries = context.core.libraries
        suspend fun roots(item: Item) = libraries.library(item.libraryId).folders.map { Paths.get(it) }
        context.addMetadataProvider(object : MetadataProvider {
            override val id = "local-metadata"
            override val priority = 0

            override suspend fun details(item: Item, file: Path): PartialDetails? {
                val roots = roots(item)
                return withContext(Dispatchers.IO) { LocalFiles.nfo(file, roots, item.kind)?.let(LocalFiles::parseNfo) }
            }

            override suspend fun image(item: Item, file: Path, kind: ImageKind): Path? {
                val roots = roots(item)
                return withContext(Dispatchers.IO) { LocalFiles.image(file, roots, kind) }
            }
        })
    }
}
