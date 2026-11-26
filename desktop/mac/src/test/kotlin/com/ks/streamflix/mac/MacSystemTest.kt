package com.ks.streamflix.mac

import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacSystemTest {
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private val launcher = "/Applications/KS StreamFlix & Co.app/Contents/MacOS/KS StreamFlix"

    @Test fun startAtLoginWritesAndRemovesTheLaunchAgent() {
        val agent = Files.createTempDirectory("agents").resolve("LaunchAgents/com.ks.streamflix.plist")
        val system = MacSystem(launcher, agent)
        assertTrue(system.canStartAtLogin)
        assertFalse(system.isStartAtLogin())
        system.setStartAtLogin(true)
        assertTrue(system.isStartAtLogin())
        // Valid XML whose program is the launcher (spaces and & kept) with --background.
        val doc = DocumentBuilderFactory.newInstance().apply { isValidating = false; setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            .newDocumentBuilder().parse(agent.toFile())
        val strings = doc.getElementsByTagName("string").let { list -> (0 until list.length).map { list.item(it).textContent } }
        assertEquals(listOf("com.ks.streamflix", launcher, "--background", "Interactive"), strings)
        if (isMac) {
            val lint = ProcessBuilder("/usr/bin/plutil", "-lint", agent.toString()).redirectErrorStream(true).start()
            assertEquals(0, lint.waitFor(), lint.inputStream.readAllBytes().decodeToString())
        }
        system.setStartAtLogin(false)
        assertFalse(system.isStartAtLogin())
    }

    @Test fun fromSourceThereIsNoStartAtLogin() {
        val agent = Files.createTempDirectory("agents").resolve("com.ks.streamflix.plist")
        val system = MacSystem(launcher = null, agentFile = agent)
        assertFalse(system.canStartAtLogin)
        system.setStartAtLogin(true)
        assertFalse(system.isStartAtLogin(), "nothing to start")
    }

    @Test fun keepAwakeHoldsCaffeinateOnlyWhileOn() {
        if (!isMac) return
        val system = MacSystem(launcher = null, agentFile = Files.createTempDirectory("agents").resolve("x.plist"))
        fun caffeinates() = ProcessHandle.current().children().anyMatch { it.info().command().orElse("").endsWith("caffeinate") }
        system.keepAwake(true)
        system.keepAwake(true) // a second "on" starts nothing more
        assertEquals(1, ProcessHandle.current().children().filter { it.info().command().orElse("").endsWith("caffeinate") }.count())
        system.keepAwake(false)
        Thread.sleep(300)
        assertFalse(caffeinates())
    }
}
