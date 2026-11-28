package com.ks.streamflix.server

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Credentials
import com.ks.streamflix.api.Session
import com.ks.streamflix.server.plugin.ServerPlugin
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * A measured value, reported by the build's `measurements` task and never failing it
 * (specs/quality-budgets.md). Without Gradle it is only printed.
 */
object Measurement {
    fun record(name: String, value: String) {
        println("measured $name: $value")
        val dir = System.getProperty("measurements.dir") ?: return
        Files.createDirectories(Path.of(dir))
        Files.writeString(Path.of(dir, "$name.txt"), value)
    }
}

/** A server on a free local port with fast password hashing, and a small HTTP client for it. */
class TestServer(val dataDir: Path = Files.createTempDirectory("sf-data"), plugins: List<ServerPlugin> = emptyList(), dev: Boolean = false) : AutoCloseable {
    val server = StreamflixServer(
        ServerConfig(dataDir = dataDir, port = 0, host = "127.0.0.1", name = "test", passwordIterations = 1_000, dev = dev),
        plugins,
    )
    val port = server.start()
    val base = "http://127.0.0.1:$port"
    val http: HttpClient get() = sharedClient
    var token: String? = null

    fun request(path: String, method: String = "GET", json: String? = null, headers: Map<String, String> = emptyMap()): HttpRequest {
        val b = HttpRequest.newBuilder(URI("$base$path"))
        token?.let { b.header("Authorization", "Bearer $it") }
        headers.forEach(b::header)
        if (json != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json))
        else b.method(method, HttpRequest.BodyPublishers.noBody())
        return b.build()
    }

    fun send(path: String, method: String = "GET", json: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        http.send(request(path, method, json, headers), HttpResponse.BodyHandlers.ofString())

    fun bytes(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> =
        http.send(request(path, headers = headers), HttpResponse.BodyHandlers.ofByteArray())

    fun setupOwner(name: String = "owner", password: String = "secret-pass") {
        val r = send(ApiV1.SETUP, "POST", ApiV1.json.encodeToString(Credentials(name, password)))
        check(r.statusCode() == 201) { "setup: ${r.statusCode()} ${r.body()}" }
        token = ApiV1.json.decodeFromString<Session>(r.body()).token
    }

    override fun close() = server.close()

    companion object {
        /** One client for all tests: a JDK 17 HttpClient cannot be closed, and each holds open files. */
        private val sharedClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    }
}

/** A folder of fake videos: files of random bytes with media names. */
fun mediaFolder(vararg files: Pair<String, Int>): Path {
    val root = Files.createTempDirectory("sf-media")
    files.forEach { (name, size) ->
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, Random(name.hashCode()).nextBytes(size))
    }
    return root
}
