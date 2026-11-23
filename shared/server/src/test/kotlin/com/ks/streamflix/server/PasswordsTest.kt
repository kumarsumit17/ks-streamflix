package com.ks.streamflix.server

import com.ks.streamflix.server.domain.Passwords
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {
    @Test fun hashAndVerify() {
        val hash = Passwords.hash("correct horse", iterations = 1_000)
        assertTrue(hash.startsWith("pbkdf2-sha256$1000$"))
        assertTrue(Passwords.verify("correct horse", hash))
        assertFalse(Passwords.verify("wrong horse", hash))
        assertFalse(Passwords.verify("correct horse", "garbage"))
        assertNotEquals(hash, Passwords.hash("correct horse", iterations = 1_000), "each hash has its own salt")
    }

    @Test fun tokensAreRandomAndHashed() {
        val a = Passwords.newToken()
        assertNotEquals(a, Passwords.newToken())
        assertTrue(a.length >= 43)
        assertNotEquals(a, Passwords.tokenHash(a))
    }
}
