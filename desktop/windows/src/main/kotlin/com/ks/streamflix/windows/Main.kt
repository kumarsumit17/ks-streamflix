package com.ks.streamflix.windows

import com.ks.streamflix.plugins.web.WebPlugin
import com.ks.streamflix.server.ServerMain
import java.nio.file.Paths

/** Windows entry point: the server, with its data in %APPDATA%\KS StreamFlix. */
fun main(args: Array<String>) {
    val appData = System.getenv("APPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
    ServerMain.run(args, Paths.get(appData, "KS StreamFlix"), plugins = listOf(WebPlugin()))
}
