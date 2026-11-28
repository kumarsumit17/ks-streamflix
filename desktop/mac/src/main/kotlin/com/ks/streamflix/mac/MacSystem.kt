package com.ks.streamflix.mac

import com.ks.streamflix.client.DesktopSystem
import com.ks.streamflix.client.DesktopVariant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * macOS: start at login through a LaunchAgent in the user's own `~/Library/LaunchAgents` (no helper
 * app, no admin rights), and no idle sleep while someone watches through `caffeinate -i`, which
 * ends by itself if the app is gone (`-w <pid>`).
 */
class MacSystem(
    private val launcher: String? = DesktopSystem.installedLauncher(),
    private val agentFile: Path = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents", "${DesktopVariant.current.id}.plist"),
    private val label: String = DesktopVariant.current.id,
) : DesktopSystem {
    private var caffeinate: Process? = null

    init {
        // The app may have moved since the agent was written: point it at this copy.
        launcher?.let { l -> if (isStartAtLogin()) runCatching { write(l) } }
    }

    override val canStartAtLogin: Boolean get() = launcher != null

    override fun isStartAtLogin(): Boolean = Files.isRegularFile(agentFile)

    override fun setStartAtLogin(on: Boolean) {
        if (on) write(launcher ?: return) else Files.deleteIfExists(agentFile)
    }

    private fun write(launcher: String) {
        Files.createDirectories(agentFile.parent)
        Files.writeString(agentFile, launchAgent(launcher, label))
    }

    @Synchronized
    override fun keepAwake(on: Boolean) {
        val running = caffeinate?.isAlive == true
        if (on && !running) {
            caffeinate = runCatching {
                ProcessBuilder("/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString()).start()
            }.getOrNull()
        } else if (!on && running) {
            caffeinate?.destroy()
            caffeinate = null
        }
    }

    companion object {
        /** The LaunchAgent: runs the app's launcher at login, without its window. */
        fun launchAgent(launcher: String, label: String): String {
            fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |    <key>Label</key>
                |    <string>$label</string>
                |    <key>ProgramArguments</key>
                |    <array>
                |        <string>${xml(launcher)}</string>
                |        <string>${DesktopSystem.BACKGROUND_FLAG}</string>
                |    </array>
                |    <key>RunAtLoad</key>
                |    <true/>
                |    <key>ProcessType</key>
                |    <string>Interactive</string>
                |</dict>
                |</plist>
                |""".trimMargin()
        }
    }
}
