package com.ks.streamflix.plugins.subtitles

import com.ks.streamflix.api.SubtitleList
import com.ks.streamflix.api.SubtitleTrack
import com.ks.streamflix.api.SubtitlesApi
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** A subtitle file next to a video: `Film.srt`, `Film.hi.srt`, `Film.en.forced.vtt`. */
data class SideCar(val path: Path, val language: String?, val forced: Boolean)

object SubtitleFiles {
    private val extensions = setOf("srt", "vtt", "ass", "ssa")

    /** The subtitle files that belong to [video], in a stable order. */
    fun sideCars(video: Path): List<SideCar> {
        val stem = video.fileName.toString().substringBeforeLast('.')
        val dir = video.parent ?: return emptyList()
        return Files.list(dir).use { files ->
            files.filter { it.isRegularFile() }.toList()
                .filter { f ->
                    val name = f.name
                    name.substringAfterLast('.').lowercase() in extensions &&
                        (name.substringBeforeLast('.') == stem || name.startsWith("$stem."))
                }
                .sortedBy { it.name }
                .map { f ->
                    val middle = f.name.substringBeforeLast('.').removePrefix(stem).trim('.').split('.').filter { it.isNotEmpty() }
                    val forced = middle.any { it.equals("forced", true) }
                    val language = middle.firstOrNull { it.length in 2..3 && it.all(Char::isLetter) && !it.equals("forced", true) }?.lowercase()
                    SideCar(f, language, forced)
                }
        }
    }

    /** SRT to WebVTT: a header, `.` for the milliseconds comma, no byte-order mark, LF line ends. */
    fun srtToVtt(srt: String): String {
        val text = srt.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val body = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})""").replace(text) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        return "WEBVTT\n\n" + body.trim() + "\n"
    }

    /** A display label from a language code (`hi` → `Hindi`), with "forced" when it is. */
    fun label(language: String?, title: String?, forced: Boolean, fallback: String): String {
        val name = title ?: language?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
        } ?: fallback
        return if (forced) "$name (forced)" else name
    }
}

/**
 * Plugin `subtitles`: lists an item's side-car files and embedded text tracks and serves each as
 * WebVTT. Side-car SRT and VTT need nothing else; ASS files and embedded tracks need FFmpeg. Image
 * subtitles (PGS, DVD) are left to transcoding (burned in, stage 4).
 */
class SubtitlesPlugin(private val ffmpeg: Ffmpeg = Ffmpeg.locate()) : ServerPlugin {
    override val id = "subtitles"

    override fun install(context: PluginContext) {
        val cache = context.dataDir.resolve("vtt")
        Files.createDirectories(cache)
        val libraries = context.core.libraries

        suspend fun tracks(itemId: Long, file: Path): List<Pair<SubtitleTrack, suspend () -> ByteArray?>> {
            val result = mutableListOf<Pair<SubtitleTrack, suspend () -> ByteArray?>>()
            SubtitleFiles.sideCars(file).forEachIndexed { n, side ->
                val ext = side.path.name.substringAfterLast('.').lowercase()
                if (ext in setOf("ass", "ssa") && ffmpeg.ffmpeg == null) return@forEachIndexed
                val id = "file-$n"
                val track = SubtitleTrack(id, side.language, SubtitleFiles.label(side.language, null, side.forced, side.path.name), side.forced, false, SubtitlesApi.track(itemId, id))
                result += track to suspend {
                    when (ext) {
                        "vtt" -> side.path.readBytes()
                        "srt" -> SubtitleFiles.srtToVtt(side.path.readBytes().decodeToString()).toByteArray()
                        else -> cached(cache, side.path, "file") { ffmpeg.ffmpegOutput(listOf("-i", side.path.toString(), "-f", "webvtt", "-")) }
                    }
                }
            }
            val streams = ffmpeg.probe(itemId, file)?.subtitles.orEmpty()
            // Picture tracks are listed for burning in (by the transcode plugin); they have no WebVTT.
            streams.filter { it.codec in Ffmpeg.IMAGE_SUBTITLES }.forEach { s ->
                val label = SubtitleFiles.label(s.language, s.title, s.forced, "Track ${s.index}")
                result += SubtitleTrack("stream-${s.index}", s.language, label, s.forced, s.default, "", burnIn = true, streamIndex = s.index) to suspend { null }
            }
            streams.filter { it.codec in Ffmpeg.TEXT_SUBTITLES }.forEach { s ->
                val id = "stream-${s.index}"
                val track = SubtitleTrack(id, s.language, SubtitleFiles.label(s.language, s.title, s.forced, "Track ${s.index}"), s.forced, s.default, SubtitlesApi.track(itemId, id), streamIndex = s.index)
                result += track to suspend {
                    cached(cache, file, id) { ffmpeg.ffmpegOutput(listOf("-i", file.toString(), "-map", "0:${s.index}", "-f", "webvtt", "-")) }
                }
            }
            return result
        }

        context.routes {
            authenticate(SESSION_AUTH) {
                route(SubtitlesApi.BASE) {
                    get("items/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
                        call.respond(SubtitleList(tracks(id, libraries.fileOf(id)).map { it.first }))
                    }
                    get("items/{id}/tracks/{track}.vtt") {
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
                        val wanted = call.parameters["track"]
                        val loader = tracks(id, libraries.fileOf(id)).firstOrNull { it.first.id == wanted }?.second
                            ?: throw DomainException("subtitles.error.notFound", DomainException.Kind.NOT_FOUND)
                        val bytes = loader() ?: throw DomainException("subtitles.error.notFound", DomainException.Kind.NOT_FOUND)
                        call.respondBytes(bytes, ContentType("text", "vtt").withParameter("charset", "utf-8"))
                    }
                }
            }
        }
    }

    /** Converted subtitles are kept, keyed by the source file, its time and the track. */
    private suspend fun cached(dir: Path, source: Path, track: String, make: suspend () -> ByteArray?): ByteArray? {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${source.toAbsolutePath()}|${Files.getLastModifiedTime(source).toMillis()}|$track".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = dir.resolve("$key.vtt")
        if (file.exists()) return file.readBytes()
        return make()?.also { Files.write(file, it) }
    }
}
