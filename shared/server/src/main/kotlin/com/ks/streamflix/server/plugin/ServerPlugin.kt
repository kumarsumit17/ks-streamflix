package com.ks.streamflix.server.plugin

import com.ks.streamflix.api.ServerInfo
import com.ks.streamflix.server.details.DetailsService
import com.ks.streamflix.server.details.MetadataProvider
import com.ks.streamflix.server.domain.AuthService
import com.ks.streamflix.server.domain.LibraryService
import com.ks.streamflix.server.domain.StreamKeys
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
    val streamKeys: StreamKeys,
    internal val details: DetailsService,
    val info: suspend () -> ServerInfo,
) {
    /** Settings pages that plugins add for the owner: (language-file key of the label, path). The `web` plugin links them. */
    val adminPages: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()
}

class PluginContext internal constructor(
    val core: Core,
    val strings: Strings,
    /** The plugin's own folder for its files, created on first use. */
    val dataDir: Path,
) {
    internal val routes = mutableListOf<Route.() -> Unit>()
    internal val startListeners = mutableListOf<(Int) -> Unit>()
    internal val stopListeners = mutableListOf<() -> Unit>()

    /** Called once the server listens, with its HTTP port. */
    fun onStarted(listener: (port: Int) -> Unit) {
        startListeners += listener
    }

    /** Adds a settings page for the owner at [path] (a route the plugin adds), labelled by the language-file key [labelKey]. */
    fun addAdminPage(labelKey: String, path: String) {
        core.adminPages += labelKey to path
    }

    /** Adds a source of item details and images (merged by the core, served at `/api/v1/items/{id}/details` and `/image/…`). */
    fun addMetadataProvider(provider: MetadataProvider) = core.details.add(provider)

    /** Called when the server stops; close what the plugin opened. */
    fun onStopped(listener: () -> Unit) {
        stopListeners += listener
    }

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

/** The cookie the browser pages keep the language choice in (empty or missing: follow the browser). */
const val LANG_COOKIE = "sf_lang"
