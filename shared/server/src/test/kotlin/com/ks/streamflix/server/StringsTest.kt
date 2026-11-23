package com.ks.streamflix.server

import com.ks.streamflix.server.lang.Language
import com.ks.streamflix.server.lang.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    private val strings = Strings(listOf("common", "core"))

    @Test fun coreAndSharedText() {
        assertEquals("The name or password is wrong.", strings.get(Language.ENGLISH, "auth.error.wrong"))
        assertEquals("नाम या पासवर्ड गलत है।", strings.get(Language.HINDI, "auth.error.wrong"))
        assertEquals("OK", strings.get(Language.ENGLISH, "common.ok"))
        assertEquals("no.such.key", strings.get(Language.HINDI, "no.such.key"))
    }

    @Test fun languageRule() {
        assertEquals(Language.HINDI, Language.resolve(null, "hi-IN,en;q=0.8"))
        assertEquals(Language.ENGLISH, Language.resolve("en", "hi-IN"))
        assertEquals(Language.HINDI, Language.resolve("hi", "en-GB"))
        assertEquals(Language.ENGLISH, Language.resolve("xx", "fr-FR,de"))
    }
}
