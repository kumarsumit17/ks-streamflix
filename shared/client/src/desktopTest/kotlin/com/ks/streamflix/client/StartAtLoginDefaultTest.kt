package com.ks.streamflix.client

import com.ks.streamflix.client.settings.MemorySettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartAtLoginDefaultTest {
    private class System : DesktopSystem {
        var atLogin = false
        override val canStartAtLogin = true
        override fun isStartAtLogin() = atLogin
        override fun setStartAtLogin(on: Boolean) { atLogin = on }
        override fun keepAwake(on: Boolean) = Unit
    }

    @Test fun productionTurnsItOnOnce() {
        val system = System()
        val settings = MemorySettings()
        applyStartAtLoginDefault(system, settings, DesktopVariant(dev = false))
        assertTrue(system.atLogin)
        // The owner turns it off: it stays off.
        system.setStartAtLogin(false)
        applyStartAtLoginDefault(system, settings, DesktopVariant(dev = false))
        assertFalse(system.atLogin)
    }

    @Test fun aDevBuildNeverTurnsItOn() {
        val system = System()
        applyStartAtLoginDefault(system, MemorySettings(), DesktopVariant(dev = true))
        assertFalse(system.atLogin)
    }
}
