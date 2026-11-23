package com.ks.streamflix.ffmpeg

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.AudioStream
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.SubtitleStream
import com.ks.streamflix.api.ToolStatus
import com.ks.streamflix.api.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The owner's FFmpeg: where `ffprobe` and `ffmpeg` are, if anywhere. */
class Ffmpeg(val ffprobe: File?, val ffmpeg: File?) {
    val version: String? by lazy { ffprobe?.let { run(listOf(it.path, "-version"), 10)?.lineSequence()?.firstOrNull() } }
    val status get() = ToolStatus(ffprobe != null, ffmpeg != null, version)

    /** Few at a time: a scan of a big library must not start hundreds of processes. */
    private val slots = Semaphore(4)

    /** Reads [file]'s streams, or null when ffprobe is missing or fails. */
    suspend fun probe(itemId: Long, file: Path): MediaInfo? {
        val probe = ffprobe ?: return null
        val json = slots.withPermit {
            withContext(Dispatchers.IO) {
                run(listOf(probe.path, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()), 60)
            }
        } ?: return null
        return runCatching { parseProbe(itemId, json) }.getOrNull()
    }

    /** Runs ffmpeg with [args] and returns its standard output, or null on failure. */
    suspend fun ffmpegOutput(args: List<String>, timeoutSeconds: Long = 120): ByteArray? {
        val exe = ffmpeg ?: return null
        return slots.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val p = ProcessBuilder(listOf(exe.path, "-nostdin", "-v", "error") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val out = p.inputStream.readBytes()
                    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
                    else if (p.exitValue() == 0) out else null
                }.getOrNull()
            }
        }
    }

    companion object {
        /**
         * Finds FFmpeg: the folder in `STREAMFLIX_FFMPEG_DIR`, then `PATH`, then the usual places
         * (Homebrew on macOS; `ffmpeg\bin` folders and winget's links on Windows).
         */
        fun locate(env: Map<String, String> = System.getenv(), windows: Boolean = System.getProperty("os.name").startsWith("Windows")): Ffmpeg {
            val exe = if (windows) ".exe" else ""
            val dirs = buildList {
                env["STREAMFLIX_FFMPEG_DIR"]?.let(::add)
                env["PATH"]?.split(File.pathSeparatorChar)?.let(::addAll)
                if (windows) {
                    listOfNotNull(env["ProgramFiles"], "C:\\").forEach { add("$it\\ffmpeg\\bin") }
                    env["LOCALAPPDATA"]?.let { add("$it\\Microsoft\\WinGet\\Links") }
                } else {
                    addAll(listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"))
                }
            }
            fun find(name: String) = dirs.asSequence().map { File(it, name + exe) }.firstOrNull { it.isFile && it.canExecute() }
            return Ffmpeg(find("ffprobe"), find("ffmpeg"))
        }

        private fun run(command: List<String>, timeoutSeconds: Long): String? = runCatching {
            val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else if (p.exitValue() == 0) out else null
        }.getOrNull()

        /** Turns ffprobe's JSON (`-show_format -show_streams`) into [MediaInfo]. */
        fun parseProbe(itemId: Long, json: String): MediaInfo {
            val root = ApiV1.json.parseToJsonElement(json).jsonObject
            val format = root["format"] as? JsonObject
            val streams = (root["streams"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            fun JsonObject.tag(key: String) = (this["tags"] as? JsonObject)?.str(key)
            fun JsonObject.flag(key: String) = ((this["disposition"] as? JsonObject)?.get(key)?.jsonPrimitive?.intOrNull ?: 0) == 1
            fun JsonObject.index() = this["index"]?.jsonPrimitive?.intOrNull ?: -1
            val video = streams.firstOrNull { it.str("codec_type") == "video" && !it.flag("attached_pic") }?.let { v ->
                val rate = v.str("avg_frame_rate")?.split('/')?.let { (n, d) -> n.toDouble() / d.toDouble().takeIf { it > 0 }!! }
                VideoStream(
                    codec = v.str("codec_name") ?: "unknown",
                    width = v["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = v["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    frameRate = rate?.takeIf { it.isFinite() }?.let { Math.round(it * 1000) / 1000.0 },
                    hdr = v.str("color_transfer") in setOf("smpte2084", "arib-std-b67"),
                )
            }
            val language = { s: JsonObject -> s.tag("language")?.takeIf { it != "und" } }
            return MediaInfo(
                itemId = itemId,
                durationMs = format?.str("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                container = format?.str("format_name"),
                bitrate = format?.str("bit_rate")?.toLongOrNull(),
                video = video,
                audio = streams.filter { it.str("codec_type") == "audio" }.map {
                    AudioStream(it.index(), it.str("codec_name") ?: "unknown", it["channels"]?.jsonPrimitive?.intOrNull, language(it), it.tag("title"), it.flag("default"))
                },
                subtitles = streams.filter { it.str("codec_type") == "subtitle" }.map {
                    SubtitleStream(it.index(), it.str("codec_name") ?: "unknown", language(it), it.tag("title"), it.flag("forced"), it.flag("default"))
                },
            )
        }

        /** Subtitle codecs that are text, so they can be turned into WebVTT (image subtitles need burning in, stage 4). */
        val TEXT_SUBTITLES = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
    }
}
