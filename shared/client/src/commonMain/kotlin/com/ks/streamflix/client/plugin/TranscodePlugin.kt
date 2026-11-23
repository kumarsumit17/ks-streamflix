package com.ks.streamflix.client.plugin

import com.ks.streamflix.api.PlayMethod
import com.ks.streamflix.api.PlaybackRequest
import com.ks.streamflix.client.net.ApiException
import com.ks.streamflix.client.net.StreamflixClient
import com.ks.streamflix.api.Item

/**
 * Plugin `transcode` (pairs with the server plugin): asks the server how to play each item on this
 * device. Direct play keeps the file; a remux or transcode plays the server's HLS stream, which has
 * the item's whole length, so seeking works as for the file. The server's session is stopped when
 * playback stops. Quiet without a device profile (the desktop apps) or without the server plugin.
 */
class TranscodePlugin : ClientPlugin {
    override val id = "transcode"

    @Volatile private var session: String? = null

    override fun install(context: ClientPluginContext) {
        val profile = context.host.deviceProfile ?: return
        context.addPlayDecorator { client, item, request ->
            session?.let { client.stopSession(it) }
            session = null
            val decision = try {
                client.playback(item.id, PlaybackRequest(profile, request.startMs, request.burnSubtitleIndex))
            } catch (e: ApiException) {
                // A server without the plugin answers 404: play the file. Other refusals (busy, no FFmpeg) are shown.
                if (e.status == 404) return@addPlayDecorator request else throw e
            }
            if (decision.method == PlayMethod.DIRECT) return@addPlayDecorator request
            session = decision.sessionId
            request.copy(url = client.url(decision.path), hls = true)
        }
        context.addPlaybackListener(object : PlaybackListener {
            override suspend fun stopped(client: StreamflixClient, item: Item, positionMs: Long, durationMs: Long) {
                session?.let { client.stopSession(it) }
                session = null
            }
        })
    }
}
