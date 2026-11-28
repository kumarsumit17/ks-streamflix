package com.ks.streamflix.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.ks.streamflix.client.plugin.ClientPlugin
import com.ks.streamflix.client.plugin.ClientPluginContext
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.client_desktop_keep_awake_hint
import com.ks.streamflix.client.resources.client_desktop_section
import com.ks.streamflix.client.resources.client_desktop_start_at_login
import com.ks.streamflix.client.resources.client_desktop_start_at_login_hint
import com.ks.streamflix.client.resources.client_desktop_start_at_login_unavailable
import com.ks.streamflix.client.settings.SettingsStore
import com.ks.streamflix.client.ui.ChoiceButton
import org.jetbrains.compose.resources.stringResource

/**
 * What the operating system offers the desktop server (specs/plan-per-platform.md, step 3). Each
 * platform folder implements it with that OS's own means: a LaunchAgent and `caffeinate` on macOS,
 * the Run key and `SetThreadExecutionState` on Windows.
 */
interface DesktopSystem {
    /** Starting at login is possible: the installed app, which knows its own launcher. */
    val canStartAtLogin: Boolean
    fun isStartAtLogin(): Boolean
    fun setStartAtLogin(on: Boolean)

    /** Keeps the computer from going to sleep while [on] (the display may still turn off). */
    fun keepAwake(on: Boolean)

    companion object {
        /** The launcher of the installed app (set by jpackage), or null when run from source. */
        fun installedLauncher(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

        /** The command-line flag a start at login passes: the server starts without its window. */
        const val BACKGROUND_FLAG = "--background"
    }
}

/**
 * Start at login is on by default in production (owner's decision), set once; Settings can turn it
 * off. A DEV build never turns it on by itself (specs/dev-variant.md).
 */
fun applyStartAtLoginDefault(system: DesktopSystem, settings: SettingsStore, variant: DesktopVariant) {
    if (variant.dev || !system.canStartAtLogin || settings.getString(LOGIN_DEFAULT_SET) != null) return
    runCatching { system.setStartAtLogin(true) }
    settings.putString(LOGIN_DEFAULT_SET, "true")
}

private const val LOGIN_DEFAULT_SET = "desktop.startAtLogin.defaultSet"

/** Settings ▸ On this computer: start at login, and a note that the computer stays awake while someone watches. */
class DesktopSystemPlugin(private val system: DesktopSystem) : ClientPlugin {
    override val id = "desktop-system"

    override fun install(context: ClientPluginContext) {
        context.addSettingsSection { Section(system) }
    }
}

@Composable
private fun Section(system: DesktopSystem) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var atLogin by remember { mutableStateOf(runCatching { system.isStartAtLogin() }.getOrDefault(false)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.client_desktop_section), style = MaterialTheme.typography.titleMedium)
        if (system.canStartAtLogin) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(stringResource(Res.string.client_desktop_start_at_login), atLogin) {
                    runCatching { system.setStartAtLogin(!atLogin) }
                    atLogin = runCatching { system.isStartAtLogin() }.getOrDefault(atLogin)
                }
            }
            Text(stringResource(Res.string.client_desktop_start_at_login_hint), style = MaterialTheme.typography.bodySmall, color = muted)
        } else {
            Text(stringResource(Res.string.client_desktop_start_at_login_unavailable), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text(stringResource(Res.string.client_desktop_keep_awake_hint), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
