package com.ks.streamflix.windows

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsSystemTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val launcher = """C:\Users\Test User\AppData\Local\KS StreamFlix\KS StreamFlix.exe"""

    @Test fun theRunValueQuotesTheLauncher() {
        assertEquals("\"$launcher\" --background", WindowsSystem.runCommand(launcher))
    }

    @Test fun theKeepAwakeScriptIsEncodedForPowerShell() {
        val script = WindowsSystem.keepAwakeScript(4242)
        assertTrue("SetThreadExecutionState([uint32]2147483649)" in script, "ES_CONTINUOUS | ES_SYSTEM_REQUIRED")
        assertTrue("Wait-Process -Id 4242" in script)
        assertEquals(script, Base64.getDecoder().decode(WindowsSystem.encoded(script)).toString(Charsets.UTF_16LE))
    }

    /** Real registry, under a value name of its own (removed again). Windows CI runs it. */
    @Test fun startAtLoginWritesAndRemovesTheRunValue() {
        if (!isWindows) return
        val system = WindowsSystem(launcher, valueName = "KS StreamFlix test ${UUID.randomUUID()}")
        assertFalse(system.isStartAtLogin())
        system.setStartAtLogin(true)
        try {
            assertTrue(system.isStartAtLogin())
        } finally {
            system.setStartAtLogin(false)
        }
        assertFalse(system.isStartAtLogin())
    }

    @Test fun keepAwakeHoldsPowerShellOnlyWhileOn() {
        if (!isWindows) return
        val system = WindowsSystem(launcher = null)
        fun holders() = ProcessHandle.current().children().filter { it.info().command().orElse("").lowercase().endsWith("powershell.exe") }.count()
        system.keepAwake(true)
        system.keepAwake(true)
        Thread.sleep(3_000) // the script has set the state and is waiting for this process
        assertEquals(1, holders(), "the PowerShell process is still running")
        system.keepAwake(false)
        Thread.sleep(1_000)
        assertEquals(0, holders())
    }
}
