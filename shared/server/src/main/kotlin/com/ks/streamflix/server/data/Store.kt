package com.ks.streamflix.server.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.domain.ParsedName
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import com.ks.streamflix.api.Item as ApiItem
import com.ks.streamflix.api.Library as ApiLibrary
import com.ks.streamflix.api.User as ApiUser

/** A library item with the server-only fields the API does not show. */
data class StoredItem(val item: ApiItem, val path: String, val modified: Long)

data class StoredUser(val user: ApiUser, val passwordHash: String)

/**
 * The data layer: SQLDelight queries ([StreamflixDb], from `src/main/sqldelight`) mapped to the API
 * models. Every call runs on one database thread through [run], so there is exactly one SQLite
 * connection, writes never wait on each other, and closing the store closes it.
 */
class Store private constructor(url: String) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "streamflix-db").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val driver: SqlDriver = executor.submit<SqlDriver> { driver(url) }.get()
    private val db = StreamflixDb(driver)

    /** Runs [block] on the database thread. */
    suspend fun <T> run(block: Store.() -> T): T = withContext(dispatcher) { block() }

    companion object {
        /** Opens (and creates or migrates) the database in [file]. */
        fun open(file: Path): Store = Store("jdbc:sqlite:${file.toAbsolutePath()}")

        /** An in-memory database, for tests. */
        fun inMemory(): Store = Store(JdbcSqliteDriver.IN_MEMORY)

        private fun driver(url: String): SqlDriver {
            val properties = Properties().apply {
                put("foreign_keys", "true")
                put("journal_mode", "WAL")
                put("busy_timeout", "5000")
            }
            // The driver keeps the schema version in SQLite's user_version: it creates the schema
            // in a new file and runs the migrations (N.sqm) on an older one.
            return JdbcSqliteDriver(url, properties, StreamflixDb.Schema)
        }
    }

    override fun close() {
        executor.submit { driver.close() }.get()
        dispatcher.close()
    }

    private fun now() = System.currentTimeMillis()

    // ---- Users and sessions ----------------------------------------------------------------------

    fun userCount(): Long = db.userQueries.countUsers().executeAsOne()

    fun addUser(name: String, passwordHash: String, owner: Boolean): ApiUser = db.transactionWithResult {
        db.userQueries.insertUser(name, passwordHash, if (owner) 1 else 0, now())
        ApiUser(db.userQueries.lastId().executeAsOne(), name, owner)
    }

    fun userByName(name: String): StoredUser? =
        db.userQueries.userByName(name).executeAsOneOrNull()?.let { StoredUser(it.toApi(), it.password_hash) }

    fun addSession(tokenHash: String, userId: Long) {
        val t = now()
        db.userQueries.insertSession(tokenHash, userId, t, t)
    }

    /** The user of a session used within [maxIdleMillis]; marks the session used now. */
    fun userBySession(tokenHash: String, maxIdleMillis: Long): ApiUser? = db.transactionWithResult {
        val t = now()
        db.userQueries.deleteIdleSessions(t - maxIdleMillis)
        db.userQueries.userBySession(tokenHash).executeAsOneOrNull()?.also {
            db.userQueries.touchSession(t, tokenHash)
        }?.toApi()
    }

    fun removeSession(tokenHash: String) {
        db.userQueries.deleteSession(tokenHash)
    }

    fun users(): List<StoredUser> = db.userQueries.allUsers().executeAsList().map { StoredUser(it.toApi(), it.password_hash) }

    fun userById(id: Long): StoredUser? = db.userQueries.userById(id).executeAsOneOrNull()?.let { StoredUser(it.toApi(), it.password_hash) }

    /** Removes a user who is not the owner, with their sessions (they cascade). */
    fun removeUser(id: Long) {
        db.userQueries.deleteUser(id)
    }

    /** A new password; every session of the user ends. */
    fun setPassword(id: Long, passwordHash: String) = db.transaction {
        db.userQueries.updatePassword(passwordHash, id)
        db.userQueries.deleteSessionsOfUser(id)
    }

    private fun User.toApi() = ApiUser(id, name, is_owner == 1L)

    // ---- Libraries -------------------------------------------------------------------------------

    fun addLibrary(name: String, kind: LibraryKind, folders: List<String>): ApiLibrary = db.transactionWithResult {
        db.libraryQueries.insertLibrary(name, kind.name, now())
        val id = db.userQueries.lastId().executeAsOne()
        folders.distinct().forEach { db.libraryQueries.insertFolder(id, it) }
        library(id)!!
    }

    fun libraries(): List<ApiLibrary> = db.libraryQueries.libraries().executeAsList().map {
        ApiLibrary(it.id, it.name, LibraryKind.valueOf(it.kind), folders(it.id), it.item_count.toInt())
    }

    fun library(id: Long): ApiLibrary? = db.libraryQueries.libraryById(id).executeAsOneOrNull()?.let {
        ApiLibrary(it.id, it.name, LibraryKind.valueOf(it.kind), folders(it.id), it.item_count.toInt())
    }

    fun libraryByName(name: String): ApiLibrary? =
        db.libraryQueries.libraryIdByName(name).executeAsOneOrNull()?.let(::library)

    /** Removes a library and its items (they cascade); the files are not touched. */
    fun removeLibrary(id: Long) {
        db.libraryQueries.deleteLibrary(id)
    }

    private fun folders(libraryId: Long) = db.libraryQueries.folders(libraryId).executeAsList()

    // ---- Items -----------------------------------------------------------------------------------

    fun items(libraryId: Long): List<StoredItem> = db.itemQueries.itemsInLibrary(libraryId).executeAsList().map { it.toStored() }

    fun item(id: Long): StoredItem? = db.itemQueries.itemById(id).executeAsOneOrNull()?.toStored()

    /** Runs [block] in one transaction: a scan writes all its changes at once. */
    fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    fun insertItem(libraryId: Long, path: String, size: Long, modified: Long, name: ParsedName) {
        db.itemQueries.insertItem(
            libraryId, path, size, modified, name.kind.name, name.title,
            name.year?.toLong(), name.series, name.season?.toLong(), name.episode?.toLong(), now(),
        )
    }

    fun updateItem(id: Long, size: Long, modified: Long, name: ParsedName) {
        db.itemQueries.updateItem(
            size, modified, name.kind.name, name.title, name.year?.toLong(), name.series,
            name.season?.toLong(), name.episode?.toLong(), id,
        )
    }

    fun removeItem(id: Long) {
        db.itemQueries.deleteItem(id)
    }

    private fun Item.toStored() = StoredItem(
        ApiItem(
            id = id,
            libraryId = library_id,
            kind = ItemKind.valueOf(kind),
            title = title,
            year = year?.toInt(),
            series = series,
            season = season?.toInt(),
            episode = episode?.toInt(),
            size = size,
            container = path.substringAfterLast('.', "").lowercase(),
        ),
        path,
        modified,
    )
}
