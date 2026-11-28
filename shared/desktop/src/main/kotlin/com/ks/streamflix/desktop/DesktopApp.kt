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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import com.ks.streamflix.client.DesktopSystem
import com.ks.streamflix.client.DesktopSystemPlugin
import com.ks.streamflix.client.DesktopVariant
import com.ks.streamflix.client.ProvideLanguage
import com.ks.streamflix.client.resources.client_desktop_tray_open
import com.ks.streamflix.client.resources.client_desktop_tray_quit
import com.ks.streamflix.client.resources.client_desktop_tray_running
import com.ks.streamflix.client.resources.client_desktop_tray_running_text
import com.ks.streamflix.client.resources.client_desktop_tray_tooltip
import com.ks.streamflix.client.settings.resolveLanguage
import java.awt.desktop.AppReopenedListener
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timerTask
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
 * and the shared app. `--headless` runs the server alone. The server runs until Quit: closing the
 * window leaves it in the menu bar (macOS) or the notification area (Windows), and `--background`
 * (a start at login) starts it there without the window. [system] is the platform's own part
 * (start at login, staying awake while someone watches; specs/plan-per-platform.md, step 3).
 */
fun runDesktopApp(args: Array<String>, dataDir: Path, serverPlugins: List<ServerPlugin>, clientPlugins: List<ClientPlugin>, system: DesktopSystem) {
    val background = DesktopSystem.BACKGROUND_FLAG in args
    val variant = DesktopVariant.current
    val options = ServerMain.parse(args.filter { it != DesktopSystem.BACKGROUND_FLAG }.toTypedArray(), dataDir, variant.dev)
    if (options.headless) return ServerMain.runHeadless(options, serverPlugins)
    val (server, port) = ServerMain.start(options, serverPlugins)
    val local = "http://localhost:$port"
    val settings = PrefsSettings()
    // The first time, the app connects to this computer's own server.
    if (settings.getString(SettingKeys.SERVER) == null) settings.putString(SettingKeys.SERVER, local)
    // Start at login is on by default (owner's decision), set once; Settings can turn it off.
    if (system.canStartAtLogin && settings.getString(LOGIN_DEFAULT_SET) == null) {
        runCatching { system.setStartAtLogin(true) }
        settings.putString(LOGIN_DEFAULT_SET, "true")
    }
    // The computer stays awake while someone watches (checked every 20 s).
    val awakeTimer = Timer("keep-awake", true).apply {
        schedule(timerTask { runCatching { system.keepAwake(server.streaming.isActive) } }, 5_000, 20_000)
    }
    val stopped = AtomicBoolean(false)
    fun stopServer() {
        if (!stopped.compareAndSet(false, true)) return
        awakeTimer.cancel()
        runCatching { system.keepAwake(false) }
        server.close()
    }
    // App updates (the server is part of this app, so it updates with it).
    val updates = com.ks.streamflix.client.desktopUpdater(settings, options.config.version) { stopServer() }
    val plugins = clientPlugins + DesktopSystemPlugin(system) + listOfNotNull(updates?.let { com.ks.streamflix.client.plugin.AppUpdatePlugin(it) })
    val vm = AppViewModel(DesktopHost(options.config.version, settings), plugins)
    val addresses = lanAddresses().map { "http://$it:$port" }.ifEmpty { listOf(local) }
    val loopbackOnly = options.config.host.startsWith("127.") || options.config.host == "localhost"
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    // macOS: Quit from the app menu or the Dock stops the server cleanly.
    if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) {
        desktop.setQuitHandler { _, response -> stopServer(); response.performQuit() }
    }

    application {
        val tray = isTraySupported
        var visible by remember { mutableStateOf(!background || !tray) }
        fun quit() {
            stopServer()
            exitApplication()
        }
        // macOS: clicking the Dock icon opens the window again.
        DisposableEffect(Unit) {
            val reopen = AppReopenedListener { visible = true }
            if (desktop?.isSupported(Desktop.Action.APP_EVENT_REOPENED) == true) desktop.addAppEventListener(reopen)
            onDispose { if (desktop?.isSupported(Desktop.Action.APP_EVENT_REOPENED) == true) desktop.removeAppEventListener(reopen) }
        }
        val state by vm.state.collectAsState()
        val trayState = rememberTrayState()
        ProvideLanguage(resolveLanguage(state.language, vm.host.preferredLanguages)) {
            val runningTitle = stringResource(Res.string.client_desktop_tray_running)
            val runningText = stringResource(Res.string.client_desktop_tray_running_text)
            if (tray) {
                Tray(
                    icon = TrayIcon,
                    state = trayState,
                    tooltip = stringResource(Res.string.client_desktop_tray_tooltip) + if (variant.dev) " DEV" else "",
                    onAction = { visible = true },
                    menu = {
                        Item(stringResource(Res.string.client_desktop_tray_open)) { visible = true }
                        Item(stringResource(Res.string.client_desktop_tray_quit)) { quit() }
                    },
                )
            }
            Window(
                visible = visible,
                onCloseRequest = {
                    if (!tray) {
                        quit()
                    } else {
                        visible = false
                        // Once: say where the server went.
                        if (settings.getString(TRAY_HINT_SHOWN) == null) {
                            settings.putString(TRAY_HINT_SHOWN, "true")
                            trayState.sendNotification(Notification(runningTitle, runningText))
                        }
                    }
                },
                title = variant.appName,
                // The app's own icon (title bar, Windows taskbar), never the framework's default.
                icon = AppIcon,
                state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
                onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && vm.back() },
            ) {
                StreamflixApp(vm, header = { ServerCard(if (loopbackOnly) listOf(local) else addresses, local) })
            }
        }
    }
}

private const val LOGIN_DEFAULT_SET = "desktop.startAtLogin.defaultSet"
private const val TRAY_HINT_SHOWN = "desktop.trayHintShown"

/** The DEV build's icon colour (as packaging/icon-dev). */
private val DevOrange = Color(0xFFC2410C)

/** The app icon, as on the installers (packaging/icon.icns, icon.ico): a play mark on a dark rounded square (orange in a DEV build). */
private object AppIcon : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        drawRoundRect(if (DesktopVariant.current.dev) DevOrange else Color(0xFF18181B), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f, h * 0.18f))
        drawPath(
            DrawPath().apply {
                moveTo(w * 0.40f, h * 0.30f)
                lineTo(w * 0.70f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.70f)
                close()
            },
            Color(0xFFFAFAFA),
        )
    }
}

/** The menu-bar / notification-area icon: a play mark in a circle, drawn (no image file). */
private object TrayIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(if (DesktopVariant.current.dev) DevOrange else Color(0xFF71717A))
        val w = size.width
        val h = size.height
        drawPath(
            DrawPath().apply {
                moveTo(w * 0.40f, h * 0.28f)
                lineTo(w * 0.74f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.72f)
                close()
            },
            Color.White,
        )
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
