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

    /** The times (s) of [file]'s video keyframes, read from the packets without decoding; null on failure. */
    suspend fun keyframes(file: Path): List<Double>? {
        val probe = ffprobe ?: return null
        val out = slots.withPermit {
            withContext(Dispatchers.IO) {
                run(listOf(probe.path, "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", file.toString()), 120)
            }
        } ?: return null
        return out.lineSequence().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size >= 2 && parts[1].startsWith("K")) parts[0].toDoubleOrNull() else null
        }.sorted().toList()
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

    /** Starts a long-running ffmpeg (a transcode) with [args]; its log goes to [log]. Null without FFmpeg. */
    fun start(args: List<String>, log: File): Process? {
        val exe = ffmpeg ?: return null
        return runCatching {
            ProcessBuilder(listOf(exe.path, "-nostdin", "-hide_banner", "-v", "error") + args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log).start()
        }.getOrNull()
    }

    /**
     * The H.264 encoder to use: the first that works on this computer (VideoToolbox on macOS;
     * NVENC, Quick Sync or AMF, then Media Foundation on Windows), else `libx264` (an FFmpeg
     * installed by hand; the bundled LGPL build has no libx264). Each candidate encodes five blank
     * frames, read from stdin as raw video, because FFmpeg lists encoders the hardware may not have.
     */
    val h264Encoder: String by lazy {
        val exe = ffmpeg ?: return@lazy "libx264"
        val listed = run(listOf(exe.path, "-hide_banner", "-encoders"), 20).orEmpty()
        val mac = System.getProperty("os.name").startsWith("Mac")
        val candidates = if (mac) listOf("h264_videotoolbox") else listOf("h264_nvenc", "h264_qsv", "h264_amf", "h264_mf")
        candidates.firstOrNull { enc -> enc in listed && encodes(exe, enc) } ?: "libx264"
    }

    /** Whether [encoder] encodes five blank 256×144 frames (no lavfi, which the bundled build leaves out). */
    private fun encodes(exe: File, encoder: String): Boolean = runCatching {
        val frame = ByteArray(256 * 144 * 3 / 2) // yuv420p
        val p = ProcessBuilder(listOf(exe.path, "-hide_banner", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "256x144", "-r", "25",
            "-i", "-", "-frames:v", "5", "-c:v", encoder, "-f", "null", "-"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        runCatching { p.outputStream.use { out -> repeat(5) { out.write(frame) } } }
        p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    companion object {
        /**
         * A path as FFmpeg should get it: with forward slashes, which it takes on Windows too. Its HLS
         * muxer finds the folder for `init.mp4` from the playlist's path, and `C:\a\b` confuses that.
         */
        fun path(p: String): String = p.replace('\\', '/')

        /**
         * Finds FFmpeg: the folder in `STREAMFLIX_FFMPEG_DIR` (to use another build), then the copy
         * bundled in the app ([bundled], the app's resources), then `PATH`, then the usual places
         * (Homebrew on macOS; `ffmpeg\bin` folders and winget's links on Windows).
         */
        fun locate(
            env: Map<String, String> = System.getenv(),
            windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
            bundled: String? = System.getProperty("compose.application.resources.dir")?.let { File(it, "ffmpeg").path },
        ): Ffmpeg {
            val exe = if (windows) ".exe" else ""
            val dirs = buildList {
                env["STREAMFLIX_FFMPEG_DIR"]?.let(::add)
                bundled?.let(::add)
                env["PATH"]?.split(File.pathSeparatorChar)?.let(::addAll)
                if (windows) {
                    listOfNotNull(env["ProgramFiles"], "C:\\").forEach { add("$it\\ffmpeg\\bin") }
                    env["LOCALAPPDATA"]?.let { add("$it\\Microsoft\\WinGet\\Links") }
                } else {
                    addAll(listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"))
                }
            }
            // A bundled program that lost its execute bit (an installer that does not keep it) gets it back.
            bundled?.let { b -> listOf("ffmpeg", "ffprobe").map { File(b, it + exe) }.filter { it.isFile && !it.canExecute() }.forEach { it.setExecutable(true, false) } }
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

        /** Subtitle codecs that are pictures: shown by burning them into the video (transcoding). */
        val IMAGE_SUBTITLES = setOf("hdmv_pgs_subtitle", "pgssub", "dvd_subtitle", "dvdsub", "dvb_subtitle", "dvbsub", "xsub")

        /** Subtitle codecs that are text, so they can be turned into WebVTT (image subtitles need burning in, stage 4). */
        val TEXT_SUBTITLES = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
    }
}
