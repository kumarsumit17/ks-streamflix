package com.ks.streamflix.windows

import com.ks.streamflix.client.DesktopSystem
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Windows: start at login through the user's own Run key (`HKCU\...\Run`, no admin rights), written
 * with `reg.exe`; no sleep while someone watches through `SetThreadExecutionState`, held by a hidden
 * PowerShell process that ends with the app. No library is added for either.
 */
class WindowsSystem(
    private val launcher: String? = DesktopSystem.installedLauncher(),
    private val valueName: String = VALUE_NAME,
) : DesktopSystem {
    private var awake: Process? = null

    init {
        // The app may have moved since the value was written: point it at this copy.
        if (launcher != null && isStartAtLogin()) runCatching { setStartAtLogin(true) }
    }

    override val canStartAtLogin: Boolean get() = launcher != null

    override fun isStartAtLogin(): Boolean = reg("query", RUN_KEY, "/v", valueName) == 0

    override fun setStartAtLogin(on: Boolean) {
        if (on) {
            val l = launcher ?: return
            check(reg("add", RUN_KEY, "/v", valueName, "/t", "REG_SZ", "/d", runCommand(l), "/f") == 0) { "reg add failed" }
        } else if (isStartAtLogin()) {
            check(reg("delete", RUN_KEY, "/v", valueName, "/f") == 0) { "reg delete failed" }
        }
    }

    @Synchronized
    override fun keepAwake(on: Boolean) {
        val running = awake?.isAlive == true
        if (on && !running) {
            awake = runCatching {
                ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded(keepAwakeScript(ProcessHandle.current().pid()))).start()
            }.getOrNull()
        } else if (!on && running) {
            awake?.destroy()
            awake = null
        }
    }

    private fun reg(vararg args: String): Int = runCatching {
        val p = ProcessBuilder(listOf("reg.exe") + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor(15, TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); -1 }
    }.getOrDefault(-1)

    companion object {
        const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
        const val VALUE_NAME = "KS StreamFlix"

        /** The Run value: the launcher, quoted (its folder has spaces), started without its window. */
        fun runCommand(launcher: String) = "\"$launcher\" ${DesktopSystem.BACKGROUND_FLAG}"

        /**
         * Asks Windows not to sleep (ES_CONTINUOUS | ES_SYSTEM_REQUIRED; the display may still turn
         * off) for as long as this script runs, and ends when the app does.
         */
        fun keepAwakeScript(appPid: Long) = """
            |${'$'}k = Add-Type -Name Power -Namespace KsStreamflix -PassThru -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
            |[void]${'$'}k::SetThreadExecutionState([uint32]2147483649)
            |Wait-Process -Id $appPid -ErrorAction SilentlyContinue
            |""".trimMargin()

        /** PowerShell's -EncodedCommand: UTF-16LE, Base64 (no quoting problems on the command line). */
        fun encoded(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    }
}
