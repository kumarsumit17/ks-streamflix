package com.ks.streamflix.server.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password hashes (PBKDF2-HMAC-SHA256, from the JDK) and session tokens. */
object Passwords {
    /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256. */
    const val DEFAULT_ITERATIONS = 600_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /** `pbkdf2-sha256$<iterations>$<salt>$<hash>`, so the iteration count can grow later. */
    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(password, salt, iterations))
    }

    /** A new random session token (256 bits, URL-safe). */
    fun newToken(): String = b64url.encodeToString(ByteArray(32).also(random::nextBytes))

    /** Only this is stored, so a copy of the database does not let anyone sign in. */
    fun tokenHash(token: String): String =
        b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
