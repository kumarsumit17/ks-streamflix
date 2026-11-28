package com.ks.streamflix.client

import com.ks.streamflix.api.DiscoveryApi

/**
 * Which desktop app this is: production, or the DEV build that runs next to it and shares nothing
 * with it (specs/dev-variant.md). The desktop builds pass `-Dstreamflix.variant`.
 */
class DesktopVariant(val dev: Boolean) {
    /** The name people see, and the data folder's name. */
    val appName = if (dev) "KS StreamFlix DEV" else "KS StreamFlix"
    /** The macOS bundle ID and LaunchAgent label. */
    val id = if (dev) "com.ks.streamflix.dev" else "com.ks.streamflix"
    /** The app's settings node; DEV's is beside production's, never inside it. */
    val settingsNode = if (dev) "com/ks/streamflix-dev/client" else "com/ks/streamflix/client"
    /** The server's HTTP and discovery port. */
    val port = if (dev) DiscoveryApi.DEV_PORT else DiscoveryApi.PORT

    companion object {
        val current = DesktopVariant(System.getProperty("streamflix.variant") == "dev")
    }
}
