package com.ks.streamflix.windows

import com.ks.streamflix.client.DesktopJaiPlayer
import com.ks.streamflix.client.plugin.JaiPlayerPlugin
import com.ks.streamflix.client.plugin.WatchStatePlugin as WatchStateClient
import com.ks.streamflix.desktop.runDesktopApp
import com.ks.streamflix.plugins.discovery.DiscoveryPlugin
import com.ks.streamflix.plugins.watchstate.WatchStatePlugin
import com.ks.streamflix.plugins.web.WebPlugin
import java.nio.file.Paths

/** Windows entry point: the server and its window, with the plugins this app includes. */
fun main(args: Array<String>) = runDesktopApp(
    args,
    dataDir = Paths.get(System.getenv("APPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString(), "KS StreamFlix"),
    serverPlugins = listOf(WebPlugin(), WatchStatePlugin(), DiscoveryPlugin()),
    clientPlugins = listOf(WatchStateClient(), JaiPlayerPlugin(DesktopJaiPlayer::isInstalled) { url, _ -> DesktopJaiPlayer.open(url) }),
)
