package com.ks.streamflix.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ks.streamflix.client.DesktopHost
import com.ks.streamflix.client.PrefsSettings
import com.ks.streamflix.client.plugin.ClientPlugin
import com.ks.streamflix.client.resources.Res
import com.ks.streamflix.client.resources.client_desktop_addresses
import com.ks.streamflix.client.resources.client_desktop_open_browser
import com.ks.streamflix.client.resources.client_desktop_server
import com.ks.streamflix.client.settings.SettingKeys
import com.ks.streamflix.client.ui.AppViewModel
import com.ks.streamflix.client.ui.CardShape
import com.ks.streamflix.client.ui.StreamflixApp
import com.ks.streamflix.client.ui.focusRing
import com.ks.streamflix.server.ServerMain
import com.ks.streamflix.server.plugin.ServerPlugin
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Path

/**
 * The macOS and Windows app: starts the server, then opens the window with the server's address
 * and the shared app. `--headless` runs the server alone.
 */
fun runDesktopApp(args: Array<String>, dataDir: Path, serverPlugins: List<ServerPlugin>, clientPlugins: List<ClientPlugin>) {
    val options = ServerMain.parse(args, dataDir)
    if (options.headless) return ServerMain.runHeadless(options, serverPlugins)
    val (server, port) = ServerMain.start(options, serverPlugins)
    val local = "http://localhost:$port"
    val settings = PrefsSettings()
    // The first time, the app connects to this computer's own server.
    if (settings.getString(SettingKeys.SERVER) == null) settings.putString(SettingKeys.SERVER, local)
    val vm = AppViewModel(DesktopHost(options.config.version, settings), clientPlugins)
    val addresses = lanAddresses().map { "http://$it:$port" }.ifEmpty { listOf(local) }
    val loopbackOnly = options.config.host.startsWith("127.") || options.config.host == "localhost"

    application {
        Window(
            onCloseRequest = { server.close(); exitApplication() },
            title = "KS StreamFlix",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && vm.back() },
        ) {
            StreamflixApp(vm, header = { ServerCard(if (loopbackOnly) listOf(local) else addresses, local) })
        }
    }
}

@Composable
private fun ServerCard(addresses: List<String>, local: String) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, CardShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.client_desktop_server), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.client_desktop_addresses, addresses.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { runCatching { Desktop.getDesktop().browse(URI("$local/")) } }, modifier = Modifier.focusRing()) {
            Text(stringResource(Res.string.client_desktop_open_browser))
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** This computer's IPv4 addresses on its networks, for other devices to connect to. */
private fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
}.getOrDefault(emptyList())
