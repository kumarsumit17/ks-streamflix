package com.ks.streamflix.server.domain

import com.ks.streamflix.api.Item
import com.ks.streamflix.api.Library
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.ScanResult
import com.ks.streamflix.api.User
import com.ks.streamflix.server.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A domain error. [code] is a key of the language files, shown to the user by the clients. */
class DomainException(val code: String, val kind: Kind) : Exception(code) {
    enum class Kind { BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, TOO_MANY }
}

/** Sign-in, sessions and the owner account. */
class AuthService(
    private val store: Store,
    private val iterations: Int = Passwords.DEFAULT_ITERATIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SESSION_IDLE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_FAILURES = 5
        private const val FAILURE_WINDOW_MILLIS = 60_000L
    }

    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun setupNeeded(): Boolean = store.run { userCount() == 0L }

    /** Creates the owner account; only while there is no user yet. */
    suspend fun createOwner(name: String, password: String): User {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 64) throw DomainException("auth.error.name", DomainException.Kind.BAD_REQUEST)
        if (password.length < 8) throw DomainException("auth.error.passwordShort", DomainException.Kind.BAD_REQUEST)
        if (!setupNeeded()) throw DomainException("auth.error.setupDone", DomainException.Kind.CONFLICT)
        val hash = cpu { Passwords.hash(password, iterations) }
        // Checked again on the database thread, so two setups at once make one owner.
        return store.run {
            if (userCount() > 0) throw DomainException("auth.error.setupDone", DomainException.Kind.CONFLICT)
            addUser(clean, hash, owner = true)
        }
    }

    /** Signs in and returns a new token. [client] (the remote address) limits repeated failures. */
    suspend fun signIn(name: String, password: String, client: String): Pair<String, User> {
        val now = clock()
        val recent = failures.computeIfAbsent(client) { CopyOnWriteArrayList() }
        recent.removeIf { it < now - FAILURE_WINDOW_MILLIS }
        if (recent.size >= MAX_FAILURES) throw DomainException("auth.error.tooMany", DomainException.Kind.TOO_MANY)
        val stored = store.run { userByName(name.trim()) }
        // Hash even for an unknown name, so the answer takes as long either way.
        val ok = cpu {
            if (stored != null) Passwords.verify(password, stored.passwordHash)
            else Passwords.verify(password, dummyHash).let { false }
        }
        if (!ok || stored == null) {
            recent.add(now)
            throw DomainException("auth.error.wrong", DomainException.Kind.UNAUTHORIZED)
        }
        recent.clear()
        val token = Passwords.newToken()
        store.run { addSession(Passwords.tokenHash(token), stored.user.id) }
        return token to stored.user
    }

    suspend fun userFor(token: String): User? = store.run { userBySession(Passwords.tokenHash(token), SESSION_IDLE_MILLIS) }

    suspend fun signOut(token: String) = store.run { removeSession(Passwords.tokenHash(token)) }

    // ---- For plugins that manage users (the core itself has only the owner) ----------------------

    suspend fun users(): List<User> = store.run { users().map { it.user } }

    suspend fun user(id: Long): User = store.run { userById(id)?.user } ?: throw DomainException("users.error.notFound", DomainException.Kind.NOT_FOUND)

    /** Adds a user who is not the owner. */
    suspend fun addUser(name: String, password: String): User {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 64) throw DomainException("auth.error.name", DomainException.Kind.BAD_REQUEST)
        if (password.length < 8) throw DomainException("auth.error.passwordShort", DomainException.Kind.BAD_REQUEST)
        val hash = cpu { Passwords.hash(password, iterations) }
        return store.run {
            if (userByName(clean) != null) throw DomainException("users.error.exists", DomainException.Kind.CONFLICT)
            addUser(clean, hash, owner = false)
        }
    }

    /** Removes a user; the owner cannot be removed. */
    suspend fun removeUser(id: Long) {
        if (user(id).owner) throw DomainException("users.error.owner", DomainException.Kind.BAD_REQUEST)
        store.run { removeUser(id) }
    }

    /** Sets a new password and ends the user's sessions. */
    suspend fun setPassword(id: Long, password: String) {
        if (password.length < 8) throw DomainException("auth.error.passwordShort", DomainException.Kind.BAD_REQUEST)
        user(id)
        val hash = cpu { Passwords.hash(password, iterations) }
        store.run { setPassword(id, hash) }
    }

    suspend fun checkPassword(id: Long, password: String): Boolean {
        val stored = store.run { userById(id) } ?: return false
        return cpu { Passwords.verify(password, stored.passwordHash) }
    }

    private val dummyHash by lazy { Passwords.hash("not a password", iterations) }
}

/** Libraries, their items, and scanning. */
class LibraryService(private val store: Store) {
    private val scanLock = Mutex()
    private val scanListeners = CopyOnWriteArrayList<suspend (Library, ScanResult) -> Unit>()

    /** Called after every scan; plugins use it (for example to read metadata of new items). */
    fun onScanned(listener: suspend (Library, ScanResult) -> Unit) {
        scanListeners += listener
    }

    suspend fun libraries(): List<Library> = store.run { libraries() }

    suspend fun library(id: Long): Library =
        store.run { library(id) } ?: throw DomainException("library.error.notFound", DomainException.Kind.NOT_FOUND)

    suspend fun addLibrary(name: String, kind: LibraryKind, folders: List<String>): Library {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 100) throw DomainException("library.error.name", DomainException.Kind.BAD_REQUEST)
        if (folders.isEmpty()) throw DomainException("library.error.noFolder", DomainException.Kind.BAD_REQUEST)
        val paths = io { folders.map { folder ->
            val path = runCatching { Paths.get(folder.trim()).toAbsolutePath().normalize() }.getOrNull()
            if (path == null || !path.isDirectory()) throw DomainException("library.error.folder", DomainException.Kind.BAD_REQUEST)
            path.toRealPath().toString()
        } }
        return store.run {
            if (libraryByName(clean) != null) throw DomainException("library.error.exists", DomainException.Kind.CONFLICT)
            addLibrary(clean, kind, paths)
        }
    }

    /** Removes a library and what the server knows about its items; the files stay where they are. */
    suspend fun removeLibrary(id: Long) {
        library(id)
        store.run { removeLibrary(id) }
    }

    suspend fun items(libraryId: Long): List<Item> {
        library(libraryId)
        return store.run { items(libraryId).map { it.item } }
    }

    suspend fun item(id: Long): Item = storedItem(id).item

    /**
     * The file of item [id], only if it still exists inside one of its library's folders (after
     * resolving links), so a changed database or a link can never serve another file.
     */
    suspend fun fileOf(id: Long): Path {
        val (stored, library) = store.run {
            val stored = item(id) ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
            stored to (library(stored.item.libraryId) ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND))
        }
        return io { checkedFile(stored.path, library.folders) }
    }

    private fun checkedFile(storedPath: String, folders: List<String>): Path {
        val real = runCatching { Paths.get(storedPath).toRealPath() }.getOrNull()
            ?: throw DomainException("item.error.missing", DomainException.Kind.NOT_FOUND)
        val inside = folders.any { real.startsWith(Paths.get(it)) }
        if (!inside || !real.isRegularFile()) throw DomainException("item.error.missing", DomainException.Kind.NOT_FOUND)
        return real
    }

    private suspend fun storedItem(id: Long) =
        store.run { item(id) } ?: throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)

    /** Scans [libraryId]: adds new files, updates changed ones, removes missing ones. One scan at a time. */
    suspend fun scan(libraryId: Long): ScanResult {
        val library = library(libraryId)
        val result = scanLock.withLock {
            val (found, missing) = io { walk(library) }
            store.run { write(library, found, missing) }
        }
        scanListeners.forEach { it(library, result) }
        return result
    }

    private data class Found(val path: String, val size: Long, val modified: Long, val name: ParsedName)

    /** The video files in [library]'s folders, and the folders that are missing. */
    private fun walk(library: Library): Pair<Map<String, Found>, List<String>> {
        val found = LinkedHashMap<String, Found>()
        val missing = mutableListOf<String>()
        for (folder in library.folders) {
            val root = Paths.get(folder)
            if (!root.isDirectory()) {
                missing += folder
                continue
            }
            // One walk that gets each file's size and time with its directory entry (one call per
            // file on Windows, where asking for them separately is slow). Links are not followed.
            Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                    if (dir != root && dir.name.startsWith(".")) java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (!attrs.isRegularFile || !NameParser.isVideo(file.name)) return java.nio.file.FileVisitResult.CONTINUE
                    val segments = root.relativize(file).map { it.toString() }
                    if (segments.any { it.startsWith(".") } || NameParser.isExtra(segments)) return java.nio.file.FileVisitResult.CONTINUE
                    val path = file.toString()
                    found[path] = Found(path, attrs.size(), attrs.lastModifiedTime().toMillis(), NameParser.parse(segments, library.kind))
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
            })
        }
        return found to missing
    }

    private fun Store.write(library: Library, found: Map<String, Found>, missing: List<String>): ScanResult {
        var added = 0
        var updated = 0
        var removed = 0
        transaction {
            val existing = items(library.id).associateBy { it.path }
            for (f in found.values) {
                val old = existing[f.path]
                when {
                    old == null -> { insertItem(library.id, f.path, f.size, f.modified, f.name); added++ }
                    // Changed files, and items whose name a newer version of the parser reads differently.
                    old.item.size != f.size || old.modified != f.modified || !sameName(old.item, f.name) -> {
                        updateItem(old.item.id, f.size, f.modified, f.name); updated++
                    }
                }
            }
            // A missing folder (an unplugged drive) never empties the library.
            if (missing.isEmpty()) {
                for ((path, old) in existing) if (path !in found) { removeItem(old.item.id); removed++ }
            }
        }
        return ScanResult(added, updated, removed, missing)
    }
}

private fun sameName(item: Item, name: ParsedName) =
    item.kind == name.kind && item.title == name.title && item.year == name.year &&
        item.series == name.series && item.season == name.season && item.episode == name.episode

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private suspend fun <T> cpu(block: () -> T): T = withContext(Dispatchers.Default) { block() }
