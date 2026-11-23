package com.ks.streamflix.server.domain

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed stream links for players that cannot send the sign-in header (KS JaiPlayer, a browser
 * tab, another player): `?key=<expiry>.<HMAC-SHA256(item, expiry)>`, valid for one item until it
 * expires. The secret is kept in the data folder, so links survive a restart.
 */
class StreamKeys(secretFile: Path, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val LIFETIME_MILLIS = 6L * 60 * 60 * 1000
    }

    private val secret: ByteArray = if (Files.exists(secretFile)) {
        Files.readAllBytes(secretFile)
    } else {
        ByteArray(32).also(SecureRandom()::nextBytes).also { bytes ->
            Files.createDirectories(secretFile.parent)
            Files.write(secretFile, bytes)
            runCatching { Files.setPosixFilePermissions(secretFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        }
    }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** A key for [itemId] and its expiry time. */
    fun create(itemId: Long): Pair<String, Long> {
        val expires = clock() + LIFETIME_MILLIS
        return "$expires.${sign(itemId, expires)}" to expires
    }

    fun isValid(itemId: Long, key: String): Boolean {
        val expires = key.substringBefore('.', "").toLongOrNull() ?: return false
        if (expires < clock()) return false
        return MessageDigest.isEqual(sign(itemId, expires).toByteArray(), key.substringAfter('.').toByteArray())
    }

    private fun sign(itemId: Long, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return b64.encodeToString(mac.doFinal("$itemId:$expires".toByteArray()))
    }
}
