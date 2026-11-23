package com.ks.streamflix.plugins.discovery

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DiscoveryAnswer
import com.ks.streamflix.api.DiscoveryApi
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Plugin `discovery`: answers [DiscoveryApi.QUESTION] broadcasts on the home network with the
 * server's name and HTTP port. It sends nothing on its own and answers nothing else.
 */
class DiscoveryPlugin(private val udpPort: Int = DiscoveryApi.PORT) : ServerPlugin {
    override val id = "discovery"

    /** The UDP port it listens on, once started (useful when [udpPort] is 0 in tests). */
    @Volatile var boundPort: Int = -1
        private set

    override fun install(context: PluginContext) {
        var socket: DatagramSocket? = null
        context.onStarted { httpPort ->
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(udpPort))
                }
            } catch (e: SocketException) {
                System.err.println("Discovery is off: UDP port $udpPort is in use (${e.message})")
                return@onStarted
            }
            socket = s
            boundPort = s.localPort
            val info = runBlocking { context.core.info() }
            val answer = ApiV1.json.encodeToString(DiscoveryAnswer(info.name, httpPort, info.version)).toByteArray()
            Thread({
                val buffer = ByteArray(256)
                while (!s.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketException) {
                        break
                    }
                    if (String(packet.data, 0, packet.length) == DiscoveryApi.QUESTION) {
                        runCatching { s.send(DatagramPacket(answer, answer.size, packet.socketAddress)) }
                    }
                }
            }, "streamflix-discovery").apply { isDaemon = true }.start()
        }
        context.onStopped { socket?.close() }
    }
}
