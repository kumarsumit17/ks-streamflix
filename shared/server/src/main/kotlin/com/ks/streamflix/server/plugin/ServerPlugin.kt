package com.ks.streamflix.server.plugin

import com.ks.streamflix.api.ServerInfo
import com.ks.streamflix.server.domain.AuthService
import com.ks.streamflix.server.domain.LibraryService
import com.ks.streamflix.server.lang.Strings
import io.ktor.server.routing.Route
import java.nio.file.Path

/**
 * A feature outside the core (specs/media-server-plan.md, "A minimal core, plugins for the rest").
 * Each app's entry point passes the plugins it includes; the core never knows which exist.
 */
interface ServerPlugin {
    /** Stable and unique: `web`, `watch-state`. Its language files are packed at `lang/<id>/`. */
    val id: String

    /** Called once, before the server starts listening. */
    fun install(context: PluginContext)
}

/** The core's services, as plugins see them. */
class Core(
    val auth: AuthService,
    val libraries: LibraryService,
    val info: suspend () -> ServerInfo,
)

class PluginContext internal constructor(
    val core: Core,
    val strings: Strings,
    /** The plugin's own folder for its files, created on first use. */
    val dataDir: Path,
) {
    internal val routes = mutableListOf<Route.() -> Unit>()

    /**
     * Adds HTTP routes. API routes go under `/api/v1/plugins/<id>/`; pages may use any path the core
     * does not. `authenticate(SESSION_AUTH)` (optional = true for pages) gives the signed-in user.
     */
    fun routes(block: Route.() -> Unit) {
        routes += block
    }
}

/** The name of the core's sign-in check for `authenticate(...)`. */
const val SESSION_AUTH = "session"

/** The cookie the browser pages keep the session token in. */
const val SESSION_COOKIE = "sf_session"
