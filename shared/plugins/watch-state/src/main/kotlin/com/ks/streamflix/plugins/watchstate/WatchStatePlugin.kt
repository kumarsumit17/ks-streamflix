package com.ks.streamflix.plugins.watchstate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ks.streamflix.api.ContinueItem
import com.ks.streamflix.api.ContinueList
import com.ks.streamflix.api.ProgressList
import com.ks.streamflix.api.ProgressUpdate
import com.ks.streamflix.api.WatchProgress
import com.ks.streamflix.api.WatchStateApi
import com.ks.streamflix.api.WatchedUpdate
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.http.visibleItem
import com.ks.streamflix.server.http.SessionPrincipal
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.Executors

/** Plugin `watch-state`: progress, watched and continue watching, per user, in its own database file. */
class WatchStatePlugin(private val clock: () -> Long = System::currentTimeMillis) : ServerPlugin {
    override val id = "watch-state"

    override fun install(context: PluginContext) {
        Files.createDirectories(context.dataDir)
        // One thread owns the one connection, as in the core's store.
        val executor = Executors.newSingleThreadExecutor { Thread(it, "streamflix-watch-state").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val driver = executor.submit<JdbcSqliteDriver> {
            JdbcSqliteDriver(
                "jdbc:sqlite:${context.dataDir.resolve("watch-state.db").toAbsolutePath()}",
                Properties().apply { put("journal_mode", "WAL"); put("busy_timeout", "5000") },
                WatchStateDb.Schema,
            )
        }.get()
        val q = WatchStateDb(driver).progressQueries
        suspend fun <T> db(block: () -> T): T = withContext(dispatcher) { block() }
        context.onStopped {
            executor.submit { driver.close() }.get()
            dispatcher.close()
        }

        fun Progress.toApi() = WatchProgress(item_id, position_ms, duration_ms, watched == 1L, updated_at)
        fun ApplicationCall.userId() = principal<SessionPrincipal>()!!.user.id
        fun ApplicationCall.itemId() = parameters["id"]?.toLongOrNull()
            ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
        val libraries = context.core.libraries

        context.routes {
            authenticate(SESSION_AUTH) {
                route(WatchStateApi.BASE) {
                    get("progress") {
                        val library = call.request.queryParameters["library"]?.toLongOrNull()
                            ?: throw DomainException("library.error.notFound", DomainException.Kind.NOT_FOUND)
                        val user = call.userId()
                        val principal = call.principal<SessionPrincipal>()!!.user
                        val ids = context.core.access.visibleItems(principal, libraries.library(library)).map { it.id }.toSet()
                        call.respond(ProgressList(db { q.forUser(user).executeAsList() }.filter { it.item_id in ids }.map { it.toApi() }))
                    }
                    get("continue") {
                        val user = call.userId()
                        val rows = db { q.inProgress(user, 40).executeAsList() }
                        // Items removed by a rescan are skipped (and forgotten).
                        val items = rows.mapNotNull { row ->
                            val item = runCatching { libraries.item(row.item_id) }
                                .onFailure { db { q.deleteItem(row.item_id) } }.getOrNull()
                            // Items this user may no longer see are left out (and kept, in case that changes).
                            item?.takeIf { context.core.access.allows(call.principal<SessionPrincipal>()!!.user, it) }?.let { ContinueItem(it, row.toApi()) }
                        }.take(20)
                        call.respond(ContinueList(items))
                    }
                    put("items/{id}") {
                        val id = call.itemId()
                        call.visibleItem(context.core, id)
                        val body = call.receive<ProgressUpdate>()
                        if (body.positionMs < 0 || body.durationMs < 0) throw DomainException("api.error.badRequest", DomainException.Kind.BAD_REQUEST)
                        val watched = body.durationMs > 0 && body.positionMs >= body.durationMs * WatchStateApi.WATCHED_SHARE
                        val user = call.userId()
                        val row = db {
                            q.upsert(user, id, if (watched) 0 else body.positionMs, body.durationMs, if (watched) 1 else 0, clock())
                            q.forItem(user, id).executeAsOne()
                        }
                        call.respond(row.toApi())
                    }
                    post("items/{id}/watched") {
                        val id = call.itemId()
                        call.visibleItem(context.core, id)
                        val body = call.receive<WatchedUpdate>()
                        val user = call.userId()
                        val row = db {
                            val old = q.forItem(user, id).executeAsOneOrNull()
                            q.upsert(user, id, 0, old?.duration_ms ?: 0, if (body.watched) 1 else 0, clock())
                            q.forItem(user, id).executeAsOne()
                        }
                        call.respond(HttpStatusCode.OK, row.toApi())
                    }
                }
            }
        }
    }
}
