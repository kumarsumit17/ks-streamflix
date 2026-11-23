package com.ks.streamflix.client

import com.ks.streamflix.client.net.normaliseServer
import com.ks.streamflix.client.settings.LanguageChoice
import com.ks.streamflix.client.settings.resolveLanguage
import com.ks.streamflix.client.ui.formatTime
import com.ks.streamflix.client.ui.resourceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientLogicTest {
    @Test fun serverAddresses() {
        assertEquals("http://192.168.1.20:8742", normaliseServer(" 192.168.1.20 "))
        assertEquals("http://nas.local:9000", normaliseServer("nas.local:9000/"))
        assertEquals("https://media.example:443", normaliseServer("https://media.example:443"))
        assertEquals("http://[::1]:8742", normaliseServer("[::1]"))
        assertNull(normaliseServer("  "))
    }

    @Test fun languageRule() {
        assertEquals("hi", resolveLanguage(LanguageChoice.System, listOf("hi-IN", "en-US")))
        assertEquals("en", resolveLanguage(LanguageChoice.System, listOf("fr-FR")))
        assertEquals("en", resolveLanguage(LanguageChoice.English, listOf("hi-IN")))
        assertEquals(LanguageChoice.System, LanguageChoice.parse("klingon"))
    }

    @Test fun namesAndTimes() {
        assertEquals("client_error_no_player", resourceName("client.error.noPlayer"))
        assertEquals("auth_error_wrong", resourceName("auth.error.wrong"))
        assertEquals("2:03", formatTime(123_000))
        assertEquals("1:02:03", formatTime(3_723_000))
    }
}
