package com.ks.streamflix.plugins.mediainfo

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.MediaInfo
import com.ks.streamflix.api.MediaInfoApi
import com.ks.streamflix.ffmpeg.Ffmpeg
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Plugin `media-info`: reads each item's duration, codecs and tracks with ffprobe after every scan,
 * and keeps them in its own folder (one JSON file per item, redone when the file changes).
 * Without FFmpeg it answers with what it has and says so at `/status`.
 */
class MediaInfoPlugin(private val ffmpeg: Ffmpeg = Ffmpeg.locate()) : ServerPlugin {
    override val id = "media-info"

    @Serializable
    private data class Cached(val size: Long, val modified: Long, val info: MediaInfo)

    override fun install(context: PluginContext) {
        val dir = context.dataDir.resolve("items")
        Files.createDirectories(dir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context.onStopped { scope.cancel() }
        val libraries = context.core.libraries

        fun cacheFile(itemId: Long): Path = dir.resolve("$itemId.json")

        suspend fun info(itemId: Long, file: Path): MediaInfo? {
            val size = Files.size(file)
            val modified = Files.getLastModifiedTime(file).toMillis()
            val cache = cacheFile(itemId)
            if (cache.exists()) {
                runCatching { ApiV1.json.decodeFromString<Cached>(cache.readText()) }.getOrNull()
                    ?.takeIf { it.size == size && it.modified == modified }?.let { return it.info }
            }
            val probed = ffmpeg.probe(itemId, file) ?: return null
            cache.writeText(ApiV1.json.encodeToString(Cached(size, modified, probed)))
            return probed
        }

        // After a scan, new and changed items are read in the background.
        libraries.onScanned { library, _ ->
            if (ffmpeg.ffprobe == null) return@onScanned
            scope.launch {
                libraries.items(library.id).forEach { item ->
                    runCatching { info(item.id, libraries.fileOf(item.id)) }
                }
            }
        }

        context.routes {
            authenticate(SESSION_AUTH) {
                route(MediaInfoApi.BASE) {
                    get("status") { call.respond(ffmpeg.status) }
                    get("items/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
                        val file = libraries.fileOf(id)
                        val info = info(id, file) ?: throw DomainException("mediaInfo.error.unavailable", DomainException.Kind.NOT_FOUND)
                        call.respond(info)
                    }
                }
            }
        }
    }
}
