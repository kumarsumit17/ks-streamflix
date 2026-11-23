package com.ks.streamflix.server.domain

import com.ks.streamflix.api.Item
import com.ks.streamflix.api.Library
import com.ks.streamflix.api.User
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A rule about who may see what, added by a plugin (for example `users`: libraries per user,
 * age ratings). Every rule must allow; the owner sees everything.
 */
interface AccessPolicy {
    suspend fun allowsLibrary(user: User, library: Library): Boolean = true
    suspend fun allowsItem(user: User, item: Item): Boolean = true
}

/** The core's check of all access rules. Without rules, every signed-in user sees everything. */
class AccessService(private val libraries: LibraryService) {
    private val policies = CopyOnWriteArrayList<AccessPolicy>()

    fun add(policy: AccessPolicy) { policies += policy }

    suspend fun allows(user: User, library: Library): Boolean =
        user.owner || policies.all { it.allowsLibrary(user, library) }

    suspend fun allows(user: User, item: Item): Boolean {
        if (user.owner) return true
        if (!allows(user, libraries.library(item.libraryId))) return false
        return policies.all { it.allowsItem(user, item) }
    }

    suspend fun visibleLibraries(user: User): List<Library> = libraries.libraries().filter { allows(user, it) }

    suspend fun visibleItems(user: User, library: Library): List<Item> {
        if (!allows(user, library)) throw DomainException("library.error.notFound", DomainException.Kind.NOT_FOUND)
        return libraries.items(library.id).filter { allows(user, it) }
    }

    /** The item, if [user] may see it; otherwise "not found", as if it did not exist. */
    suspend fun item(user: User, id: Long): Item {
        val item = libraries.item(id)
        if (!allows(user, item)) throw DomainException("item.error.notFound", DomainException.Kind.NOT_FOUND)
        return item
    }
}
