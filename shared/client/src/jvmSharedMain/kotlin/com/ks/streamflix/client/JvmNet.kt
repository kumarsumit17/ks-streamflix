package com.ks.streamflix.client

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.DiscoveryAnswer
import com.ks.streamflix.api.DiscoveryApi
import com.ks.streamflix.client.net.HttpResponse
import com.ks.streamflix.client.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI

/** HTTP on java.net, the same on desktop and Android: no HTTP library in the apps. */
class UrlConnectionTransport(private val timeoutMs: Int = 10_000) : HttpTransport {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                headers.forEach(c::setRequestProperty)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                HttpResponse(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
            } finally {
                c.disconnect()
            }
        }
}

/**
 * Finds servers on the home network: asks [DiscoveryApi.QUESTION] by UDP broadcast (to every
 * network's broadcast address, 255.255.255.255 and this device) and collects answers for [waitMs].
 */
suspend fun discoverServers(waitMs: Int = 1_500, port: Int = DiscoveryApi.PORT): List<FoundServer> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, FoundServer>()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 300
        val question = DiscoveryApi.QUESTION.toByteArray()
        val targets = buildSet {
            add(InetAddress.getByName("255.255.255.255"))
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach(::add)
            }
            add(InetAddress.getLoopbackAddress())
        }
        targets.forEach { runCatching { socket.send(DatagramPacket(question, question.size, it, port)) } }
        val end = System.currentTimeMillis() + waitMs
        val buffer = ByteArray(512)
        while (System.currentTimeMillis() < end) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val answer = runCatching { ApiV1.json.decodeFromString<DiscoveryAnswer>(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            val host = packet.address.hostAddress.let { if (':' in it) "[$it]" else it }
            val address = "http://$host:${answer.port}"
            // The same server answers on several addresses; keep one, preferring a real network address.
            val existing = found[answer.name]
            if (existing == null || existing.address.contains("127.0.0.1")) found[answer.name] = FoundServer(answer.name, address)
        }
    }
    found.values.toList()
}
