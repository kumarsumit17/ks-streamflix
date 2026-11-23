package com.ks.streamflix.server.domain

import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.LibraryKind

/** What a file's path says about it, before any metadata is read. */
data class ParsedName(
    val kind: ItemKind,
    val title: String,
    val year: Int? = null,
    val series: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Reads titles from file and folder names.
 *
 * Films: `Film Name (2019).mkv`, `Film.Name.2019.1080p.mkv`, or `Film Name (2019)/film.mkv`.
 * Series: `Show/Season 01/Show S01E02 Title.mkv`, `Show/Show 1x02.mkv`, `Show S01E02.mkv`.
 */
object NameParser {
    val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "wmv", "mpg", "mpeg", "flv", "ogv", "3gp",
    )

    private val yearRegex = Regex("""(?<![0-9])[(\[]?((?:19|20)[0-9]{2})[)\]]?(?![0-9])""")
    private val episodeRegex = Regex("""(?i)(?<![a-z0-9])s([0-9]{1,2})[ ._-]?e([0-9]{1,3})(?![0-9])""")
    private val crossRegex = Regex("""(?i)(?<![a-z0-9])([0-9]{1,2})x([0-9]{1,3})(?![0-9])""")
    private val seasonFolderRegex = Regex("""(?i)^(?:season|series|s)[ ._-]*([0-9]{1,2})$""")

    /** Words that start the release details of a name (quality, source, codec); the title ends before them. */
    private val tags = setOf(
        "2160p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hdr", "hdr10", "dv",
        "bluray", "blu-ray", "bdrip", "brrip", "webrip", "web-dl", "webdl", "web", "hdtv", "dvdrip", "dvd", "remux",
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "aac", "ac3", "dts", "atmos",
        "proper", "repack", "extended", "unrated", "remastered", "imax",
    )

    fun isVideo(fileName: String): Boolean =
        !fileName.startsWith(".") && fileName.substringAfterLast('.', "").lowercase() in videoExtensions

    /** A sample or trailer next to the real file, skipped by the scanner. */
    fun isExtra(segments: List<String>): Boolean {
        val stem = segments.last().substringBeforeLast('.').lowercase()
        if (segments.dropLast(1).any { it.lowercase() in setOf("sample", "samples", "trailers", "extras") }) return true
        return Regex("""(?:^|[ ._-])(sample|trailer)$""").containsMatchIn(stem)
    }

    /**
     * Parses [segments], the path of a file relative to its library folder (folders first, the file
     * name last), for a library of [kind].
     */
    fun parse(segments: List<String>, kind: LibraryKind): ParsedName {
        require(segments.isNotEmpty())
        return when (kind) {
            LibraryKind.MOVIES -> movie(segments)
            LibraryKind.SHOWS -> episode(segments) ?: movie(segments).copy(kind = ItemKind.EPISODE)
        }
    }

    private fun movie(segments: List<String>): ParsedName {
        val stem = segments.last().substringBeforeLast('.')
        val fromFile = titleAndYear(stem)
        if (fromFile.second != null || segments.size == 1) return ParsedName(ItemKind.MOVIE, fromFile.first, fromFile.second)
        // `Film Name (2019)/film.mkv`: the folder has the better name.
        val fromFolder = titleAndYear(segments[segments.size - 2])
        return if (fromFolder.second != null) ParsedName(ItemKind.MOVIE, fromFolder.first, fromFolder.second)
        else ParsedName(ItemKind.MOVIE, fromFile.first, null)
    }

    private fun episode(segments: List<String>): ParsedName? {
        val stem = segments.last().substringBeforeLast('.')
        val match = episodeRegex.find(stem) ?: crossRegex.find(stem) ?: return null
        val season = match.groupValues[1].toInt()
        val episode = match.groupValues[2].toInt()
        val before = clean(stem.substring(0, match.range.first))
        val after = cutAtTags(clean(stem.substring(match.range.last + 1)), title = false)
        val folders = segments.dropLast(1).filterNot { seasonFolderRegex.matches(it.trim()) }
        val series = folders.firstOrNull()?.let { titleAndYear(it).first }?.takeIf { it.isNotEmpty() }
            ?: before.let(::cutAtTags).takeIf { it.isNotEmpty() }
            ?: stem
        val title = after.ifEmpty { "$series S%02dE%02d".format(season, episode) }
        return ParsedName(ItemKind.EPISODE, title, null, series, season, episode)
    }

    /** The title and year in a name such as `Film.Name.2019.1080p` or `Film Name (2019)`. */
    private fun titleAndYear(name: String): Pair<String, Int?> {
        val text = clean(name)
        // The last year-like number is the year, so `2001 A Space Odyssey (1968)` keeps its title.
        val match = yearRegex.findAll(text).lastOrNull { it.range.first > 0 }
        if (match != null) {
            val title = cutAtTags(text.substring(0, match.range.first).trim(' ', '-', '(', '['))
            if (title.isNotEmpty()) return title to match.groupValues[1].toInt()
        }
        return cutAtTags(text).ifEmpty { text } to null
    }

    /** Dots and underscores become spaces when the name has no spaces of its own. */
    private fun clean(name: String): String {
        val spaced = if (' ' !in name) name.replace('.', ' ').replace('_', ' ') else name.replace('_', ' ')
        return spaced.replace(Regex("""\s+"""), " ").trim(' ', '-', '.')
    }

    /**
     * The text before the first release tag. A film or series title never starts with release
     * details, so its first word ("DTS", "4K") is kept ([title]); after an episode number it is not.
     */
    private fun cutAtTags(text: String, title: Boolean = true): String {
        val words = text.split(' ')
        val end = words.withIndex().indexOfFirst { (i, w) -> (i > 0 || !title) && w.lowercase().trim('[', ']', '(', ')') in tags }
        val kept = if (end < 0) words else words.take(end)
        return kept.joinToString(" ").trim(' ', '-', '(', '[')
    }
}
