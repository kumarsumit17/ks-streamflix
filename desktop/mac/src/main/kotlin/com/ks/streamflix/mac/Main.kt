package com.ks.streamflix.mac

import com.ks.streamflix.plugins.web.WebPlugin
import com.ks.streamflix.server.ServerMain
import java.nio.file.Paths

/** macOS entry point: the server, with its data in ~/Library/Application Support/KS StreamFlix. */
fun main(args: Array<String>) {
    val data = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "KS StreamFlix")
    ServerMain.run(args, data, plugins = listOf(WebPlugin()))
}
