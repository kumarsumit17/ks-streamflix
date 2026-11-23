package com.ks.streamflix.plugins.discovery

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DiscoveryAnswer
import com.ks.streamflix.api.DiscoveryApi
import com.ks.streamflix.server.TestServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscoveryPluginTest {
    @Test fun answersTheQuestionOnly() {
        val plugin = DiscoveryPlugin(udpPort = 0)
        TestServer(Files.createTempDirectory("sf-data"), listOf(plugin)).use { s ->
            DatagramSocket().use { client ->
                client.soTimeout = 2_000
                fun ask(text: String) {
                    val bytes = text.toByteArray()
                    client.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), plugin.boundPort))
                }
                ask(DiscoveryApi.QUESTION)
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                client.receive(packet)
                val answer = ApiV1.json.decodeFromString<DiscoveryAnswer>(String(packet.data, 0, packet.length))
                assertEquals(DiscoveryAnswer("test", s.port, "0.0.0"), answer)

                client.soTimeout = 500
                ask("something else")
                assertFailsWith<SocketTimeoutException> { client.receive(DatagramPacket(buffer, buffer.size)) }
            }
        }
    }
}
