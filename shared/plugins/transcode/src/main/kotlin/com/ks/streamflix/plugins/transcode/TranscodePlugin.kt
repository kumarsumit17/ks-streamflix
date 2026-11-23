package com.ks.streamflix.plugins.transcode

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.api.PlaybackDecision
import com.ks.streamflix.api.PlaybackRequest
import com.ks.streamflix.api.TranscodeApi
import com.ks.streamflix.api.TranscodeStatus
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.http.visibleItem
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Plugin `transcode`: decides direct play, remux or transcode for each client, and serves the last
 * two as HLS (fMP4 segments of about 4 s). The whole playlist is written at once, as for a normal
 * video, so every player can see the length and seek anywhere: FFmpeg makes the segments in order,
 * and a request far ahead of it restarts FFmpeg at that segment.
 *
 * At most [maxRunning] sessions run at once; a session nobody has asked for anything for
 * [idleMillis] is stopped and its files deleted. A session id is random (128 bits) and is the only
 * key to its files, like a stream link.
 */
class TranscodePlugin(
    private val ffmpeg: Ffmpeg = Ffmpeg.locate(),
    private val maxRunning: Int = 2,
    private val idleMillis: Long = 10 * 60 * 1000L,
) : ServerPlugin {
    override val id = "transcode"

    private class Session(
        val id: String,
        val dir: Path,
        val plan: Plan,
        val input: Path,
        val starts: List<Double>,
        val playlist: String,
    ) {
        @Volatile var lastUsed = System.currentTimeMillis()
        @Volatile var process: Process? = null
        /** The segment the running FFmpeg started at. */
        @Volatile var runStart = 0
        /** Segments known to be complete (FFmpeg lists a segment only once it is written). */
        val done: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    override fun install(context: PluginContext) {
        val root = context.dataDir.resolve("sessions")
        // Sessions do not survive a restart: anything left from the last run is removed.
        root.toFile().deleteRecursively()
        Files.createDirectories(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            while (isActive) {
                delay(15_000)
                val now = System.currentTimeMillis()
                sessions.values.filter { now - it.lastUsed > idleMillis }.forEach { stop(it.id) }
            }
        }
        context.onStopped {
            scope.cancel()
            sessions.keys.toList().forEach(::stop)
        }
        val libraries = context.core.libraries

        context.routes {
            authenticate(SESSION_AUTH) {
                route(TranscodeApi.BASE) {
                    get("status") {
                        call.respond(TranscodeStatus(ffmpeg.ffmpeg != null, if (ffmpeg.ffmpeg != null) ffmpeg.h264Encoder else "", sessions.size, maxRunning))
                    }
                    post("items/{id}/playback") {
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
                        val item = call.visibleItem(context.core, id)
                        val file = libraries.fileOf(id)
                        val request = call.receive<PlaybackRequest>()
                        val info = ffmpeg.probe(id, file)
                        // Without FFmpeg (or a file it cannot read) the client gets the file as it is.
                        if (info == null) return@post call.respond(PlaybackDecision(PlayMethod.DIRECT, ApiV1.stream(id)))
                        val plan = Decision.plan(info, item.container, request.profile, request.burnSubtitleIndex)
                        val duration = info.durationMs
                        if (plan.method == PlayMethod.DIRECT || duration == null || duration <= 0) {
                            return@post call.respond(PlaybackDecision(PlayMethod.DIRECT, ApiV1.stream(id), durationMs = duration))
                        }
                        val seconds = duration / 1000.0
                        val keyframes = if (plan.copyVideo) ffmpeg.keyframes(file) else null
                        val starts = Decision.segmentStarts(seconds, keyframes)
                        val start = request.startMs.coerceIn(0, duration)
                        val session = startSession(root, plan, file, starts, Decision.playlist(starts, seconds), indexAt(starts, start / 1000.0))
                        call.respond(
                            PlaybackDecision(plan.method, "${TranscodeApi.session(session.id)}/index.m3u8", session.id, start, duration, plan.reasons),
                        )
                    }
                    delete("sessions/{sid}") {
                        call.parameters["sid"]?.let(::stop)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            // The session's files: the id is the key (players fetch these without the app's help).
            get("${TranscodeApi.BASE}/sessions/{sid}/{file}") {
                val session = sessions[call.parameters["sid"]] ?: throw DomainException("transcode.error.gone", DomainException.Kind.NOT_FOUND)
                session.lastUsed = System.currentTimeMillis()
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                val name = call.parameters["file"].orEmpty()
                when {
                    name == "index.m3u8" -> call.respondText(session.playlist, ContentType("application", "vnd.apple.mpegurl"))
                    name == "init.mp4" -> {
                        val init = session.dir.resolve(name)
                        waitFor(session) { init.isRegularFile() && Files.size(init) > 0 }
                        call.respond(LocalPathContent(init, ContentType("video", "mp4")))
                    }
                    Regex("""seg_\d{5}\.m4s""").matches(name) -> {
                        val index = name.substring(4, 9).toInt()
                        if (index !in session.starts.indices) throw DomainException("common.error.notFound", DomainException.Kind.NOT_FOUND)
                        segment(session, index)
                        call.respond(LocalPathContent(session.dir.resolve(name), ContentType("video", "iso.segment")))
                    }
                    else -> throw DomainException("common.error.notFound", DomainException.Kind.NOT_FOUND)
                }
            }
        }
    }

    private fun indexAt(starts: List<Double>, seconds: Double) = starts.indexOfLast { it <= seconds + 0.001 }.coerceAtLeast(0)

    /** Waits until segment [index] is written; restarts FFmpeg there when the running one is behind or past it. */
    private suspend fun segment(session: Session, index: Int) {
        // One decision at a time per session: players fetch several segments at once.
        synchronized(session) {
            refresh(session)
            if (index in session.done) return
            val running = session.process?.isAlive == true
            val produced = session.done.filter { it >= session.runStart }.maxOrNull() ?: (session.runStart - 1)
            // Close ahead of FFmpeg: wait. Behind where it started, or far ahead: start again from there.
            if (!running || index < session.runStart || index > produced + 3) run(session, index)
        }
        waitFor(session) { refresh(session); index in session.done }
    }

    /** Reads FFmpeg's own playlist: every segment it lists is complete. */
    private fun refresh(session: Session) {
        val own = session.dir.resolve("ffmpeg.m3u8")
        if (!own.isRegularFile()) return
        Regex("""seg_(\d{5})\.m4s""").findAll(own.readText()).forEach { session.done += it.groupValues[1].toInt() }
    }

    private suspend fun waitFor(session: Session, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!ready()) {
            val p = session.process
            if (p != null && !p.isAlive && !ready()) {
                if (p.exitValue() != 0) throw DomainException("transcode.error.failed", DomainException.Kind.NOT_FOUND)
                // Finished: whatever is not there now will not come.
                if (!ready()) throw DomainException("common.error.notFound", DomainException.Kind.NOT_FOUND)
            }
            if (System.currentTimeMillis() > deadline) throw DomainException("transcode.error.failed", DomainException.Kind.NOT_FOUND)
            delay(50)
        }
    }

    private fun run(session: Session, index: Int) = synchronized(session) {
        session.process?.let { kill(it) }
        // FFmpeg's playlist of the old run is replaced; the segments it finished stay valid.
        Files.deleteIfExists(session.dir.resolve("ffmpeg.m3u8"))
        val encoder = if (session.plan.copyVideo) "copy" else ffmpeg.h264Encoder
        val args = Decision.ffmpegArgs(session.plan, session.input.toString(), index, session.starts[index], session.dir.toString(), encoder)
        session.runStart = index
        session.process = ffmpeg.start(args, session.dir.resolve("ffmpeg.log").toFile())
            ?: throw DomainException("transcode.error.failed", DomainException.Kind.CONFLICT)
    }

    private fun startSession(root: Path, plan: Plan, file: Path, starts: List<Double>, playlist: String, firstIndex: Int): Session {
        if (ffmpeg.ffmpeg == null) throw DomainException("transcode.error.noFfmpeg", DomainException.Kind.CONFLICT)
        synchronized(sessions) {
            if (sessions.size >= maxRunning) {
                // The oldest session gives way when it has been quiet for a minute; otherwise the server is busy.
                val oldest = sessions.values.minByOrNull { it.lastUsed }
                if (oldest != null && System.currentTimeMillis() - oldest.lastUsed > 60_000) stop(oldest.id)
                else throw DomainException("transcode.error.busy", DomainException.Kind.TOO_MANY)
            }
            val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val session = Session(id, Files.createDirectories(root.resolve(id)), plan, file, starts, playlist)
            run(session, firstIndex)
            sessions[id] = session
            return session
        }
    }

    private fun kill(p: Process) {
        p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    private fun stop(id: String) {
        val session = sessions.remove(id) ?: return
        session.process?.let(::kill)
        session.dir.toFile().deleteRecursively()
    }

    /** Running sessions, for tests. */
    val running: Int get() = sessions.size
}
