package com.ks.streamflix.plugins.users

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.Library
import com.ks.streamflix.api.NewUser
import com.ks.streamflix.api.PasswordChange
import com.ks.streamflix.api.User
import com.ks.streamflix.api.UserList
import com.ks.streamflix.api.UserSettings
import com.ks.streamflix.api.UserUpdate
import com.ks.streamflix.api.UsersApi
import com.ks.streamflix.server.domain.AccessPolicy
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.http.SessionPrincipal
import com.ks.streamflix.server.lang.Language
import com.ks.streamflix.server.lang.Strings
import com.ks.streamflix.server.plugin.Core
import com.ks.streamflix.server.plugin.LANG_COOKIE
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Age certifications, as written in many countries, turned into the age they are for. */
object Certifications {
    private val ages: Map<String, Int> = buildMap {
        listOf("G", "U", "TV-Y", "TV-G", "0", "ALL", "A.L.", "FSK 0", "TP").forEach { put(it, 0) }
        listOf("TV-Y7", "7", "6", "FSK 6").forEach { put(it, 7) }
        listOf("PG", "TV-PG", "8", "10").forEach { put(it, 10) }
        listOf("12", "12A", "UA", "U/A", "UA 13+", "11", "FSK 12").forEach { put(it, 12) }
        listOf("PG-13", "13", "TV-14", "14").forEach { put(it, 14) }
        listOf("15", "16", "FSK 16").forEach { put(it, 16) }
        listOf("R", "TV-MA", "17").forEach { put(it, 17) }
        listOf("18", "NC-17", "A", "R18", "X", "FSK 18", "S").forEach { put(it, 18) }
    }

    /** The age for a certification, or null when it is not known. */
    fun age(certification: String?): Int? {
        val c = certification?.trim()?.uppercase()?.removePrefix("RATED ")?.ifEmpty { null } ?: return null
        return ages[c] ?: c.removeSuffix("+").toIntOrNull()?.takeIf { it in 0..21 }
    }
}

/**
 * Plugin `users`: the owner adds users, chooses the libraries each one sees and an age limit (from
 * the items' certifications, read by the metadata plugins). The rules are an access policy, so the
 * core and every plugin keep them. Each user can change their own password.
 */
class UsersPlugin : ServerPlugin {
    override val id = "users"

    @Serializable
    private data class Rules(val libraries: List<Long>? = null, val maxAge: Int? = null, val blockUnrated: Boolean = false)

    override fun install(context: PluginContext) {
        val core = context.core
        val file = context.dataDir.resolve("rules.json").also { Files.createDirectories(it.parent) }
        val rules = ConcurrentHashMap<Long, Rules>()
        if (file.exists()) runCatching { ApiV1.json.decodeFromString<Map<Long, Rules>>(file.readText()) }.getOrNull()?.let(rules::putAll)
        fun save() = synchronized(rules) { file.writeText(ApiV1.json.encodeToString(rules.toMap())) }
        // Item ages, read once from the metadata plugins and forgotten after every scan.
        val ages = ConcurrentHashMap<Long, Int>()
        val unknown = -1
        core.libraries.onScanned { _, _ -> ages.clear() }

        context.addAccessPolicy(object : AccessPolicy {
            override suspend fun allowsLibrary(user: User, library: Library): Boolean =
                rules[user.id]?.libraries?.contains(library.id) ?: true

            override suspend fun allowsItem(user: User, item: Item): Boolean {
                val r = rules[user.id] ?: return true
                val max = r.maxAge ?: return true
                val age = ages.getOrPut(item.id) { Certifications.age(runCatching { core.itemDetails(item).certification }.getOrNull()) ?: unknown }
                return if (age == unknown) !r.blockUnrated else age <= max
            }
        })

        suspend fun settings(user: User) = rules[user.id].let { UserSettings(user.id, user.name, user.owner, it?.libraries, it?.maxAge, it?.blockUnrated ?: false) }
        suspend fun apply(id: Long, libraries: List<Long>?, maxAge: Int?, blockUnrated: Boolean) {
            if (core.auth.user(id).owner) return // the owner sees everything
            rules[id] = Rules(libraries?.distinct(), maxAge?.coerceIn(0, 21), blockUnrated)
            save()
        }
        suspend fun remove(id: Long) {
            core.auth.removeUser(id)
            rules.remove(id)
            save()
        }

        context.addAdminPage("users.page.title", "/plugins/users")
        val strings = context.strings
        context.routes {
            authenticate(SESSION_AUTH) {
                route(UsersApi.BASE) {
                    put("me/password") {
                        val me = call.principal<SessionPrincipal>()!!.user
                        val body = call.receive<PasswordChange>()
                        if (!core.auth.checkPassword(me.id, body.current)) throw DomainException("users.error.wrongPassword", DomainException.Kind.FORBIDDEN)
                        core.auth.setPassword(me.id, body.new)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get {
                        call.requireOwner()
                        call.respond(UserList(core.auth.users().map { settings(it) }))
                    }
                    post {
                        call.requireOwner()
                        val body = call.receive<NewUser>()
                        val user = core.auth.addUser(body.name, body.password)
                        apply(user.id, body.libraries, body.maxAge, body.blockUnrated)
                        call.respond(HttpStatusCode.Created, settings(user))
                    }
                    put("{id}") {
                        call.requireOwner()
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw DomainException("users.error.notFound", DomainException.Kind.NOT_FOUND)
                        val body = call.receive<UserUpdate>()
                        apply(id, body.libraries, body.maxAge, body.blockUnrated)
                        body.password?.takeIf { it.isNotEmpty() }?.let { core.auth.setPassword(id, it) }
                        call.respond(settings(core.auth.user(id)))
                    }
                    delete("{id}") {
                        call.requireOwner()
                        remove(call.parameters["id"]?.toLongOrNull() ?: throw DomainException("users.error.notFound", DomainException.Kind.NOT_FOUND))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            // The owner's page in the browser.
            authenticate(SESSION_AUTH, optional = true) {
                get("/plugins/users") {
                    val user = call.principal<SessionPrincipal>()?.user ?: return@get call.respondRedirect("/login")
                    if (!user.owner) return@get call.respondText("", status = HttpStatusCode.Forbidden)
                    call.page(strings, core, core.auth.users().map { settings(it) }, call.request.queryParameters["error"])
                }
                post("/plugins/users/{action}") {
                    val user = call.principal<SessionPrincipal>()?.user ?: return@post call.respondRedirect("/login")
                    val origin = call.request.headers[HttpHeaders.Origin]
                    if (!user.owner || (origin != null && origin != "null" && URI(origin).host != call.request.host())) {
                        return@post call.respondText("", status = HttpStatusCode.Forbidden)
                    }
                    val form = call.receiveParameters()
                    val error = runCatching {
                        when (call.parameters["action"]) {
                            "add" -> {
                                val created = core.auth.addUser(form["name"].orEmpty(), form["password"].orEmpty())
                                apply(created.id, form.libraries(), form["maxAge"]?.toIntOrNull(), form["blockUnrated"] == "on")
                            }
                            "update" -> {
                                val id = form["id"]?.toLongOrNull() ?: throw DomainException("users.error.notFound", DomainException.Kind.NOT_FOUND)
                                apply(id, form.libraries(), form["maxAge"]?.toIntOrNull(), form["blockUnrated"] == "on")
                                form["password"]?.takeIf { it.isNotEmpty() }?.let { core.auth.setPassword(id, it) }
                            }
                            "remove" -> remove(form["id"]?.toLongOrNull() ?: throw DomainException("users.error.notFound", DomainException.Kind.NOT_FOUND))
                            else -> throw DomainException("common.error.notFound", DomainException.Kind.NOT_FOUND)
                        }
                    }.exceptionOrNull()?.let { (it as? DomainException)?.code ?: "common.error.generic" }
                    call.respondRedirect("/plugins/users" + (error?.let { "?error=$it" } ?: ""))
                }
            }
        }
    }

    /** "All libraries" when every box is ticked or the form says so; otherwise the ticked ones. */
    private fun Parameters.libraries(): List<Long>? =
        if (this["allLibraries"] == "on") null else getAll("library").orEmpty().mapNotNull { it.toLongOrNull() }

    private fun ApplicationCall.requireOwner() {
        if (principal<SessionPrincipal>()?.user?.owner != true) throw DomainException("auth.error.ownerOnly", DomainException.Kind.FORBIDDEN)
    }

    // ---- The owner's page ---------------------------------------------------------------------------

    private suspend fun ApplicationCall.page(strings: Strings, core: Core, users: List<UserSettings>, error: String?) {
        val lang = Language.resolve(request.cookies[LANG_COOKIE], request.headers[HttpHeaders.AcceptLanguage])
        fun t(key: String, params: Map<String, Any> = emptyMap()) = esc(strings.get(lang, key, params))
        val libraries = core.libraries.libraries()
        val ages = listOf(null, 0, 7, 10, 12, 14, 16, 17)
        fun rulesForm(u: UserSettings?): String = buildString {
            append("<fieldset><legend>${t("users.page.libraries")}</legend>")
            append("<label class=row><input type=checkbox name=allLibraries ${if (u?.libraries == null) "checked" else ""}> ${t("users.page.allLibraries")}</label>")
            libraries.forEach { l ->
                val on = u?.libraries?.contains(l.id) == true
                append("<label class=row><input type=checkbox name=library value=${l.id} ${if (on) "checked" else ""}> ${esc(l.name)}</label>")
            }
            append("</fieldset><label>${t("users.page.maxAge")}<select name=maxAge>")
            ages.forEach { a ->
                val label = if (a == null) t("users.page.noLimit") else t("users.page.age", mapOf("age" to a.toString()))
                append("<option value=\"${a ?: ""}\" ${if (u?.maxAge == a) "selected" else ""}>$label</option>")
            }
            append("</select></label><label class=row><input type=checkbox name=blockUnrated ${if (u?.blockUnrated == true) "checked" else ""}> ${t("users.page.blockUnrated")}</label>")
        }
        val html = buildString {
            append("""<!doctype html><html lang=${lang.code}><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>${t("users.page.title")} · KS StreamFlix</title><style>
:root{color-scheme:light dark;--b:#e4e4e7;--m:#71717a}@media(prefers-color-scheme:dark){:root{--b:#27272a;--m:#a1a1aa}}
body{font:15px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI","Noto Sans Devanagari",sans-serif;max-width:720px;margin:2rem auto;padding:0 1rem}
form{display:grid;gap:.75rem}label{display:grid;gap:.3rem;font-weight:500}fieldset{border:1px solid var(--b);border-radius:8px;display:grid;gap:.3rem}
input[type=text],input[type=password],select{height:2.25rem;padding:0 .75rem;border:1px solid var(--b);border-radius:8px;background:transparent;color:inherit;font:inherit}
button{height:2.25rem;border:0;border-radius:8px;background:#18181b;color:#fafafa;font:inherit;font-weight:500;padding:0 1rem;justify-self:start}
@media(prefers-color-scheme:dark){button{background:#fafafa;color:#18181b}}button.ghost{background:transparent;color:inherit;border:1px solid var(--b)}
.card{border:1px solid var(--b);border-radius:10px;padding:1rem;margin:1rem 0}.m{color:var(--m);font-size:13px}.e{color:#dc2626}.row{display:flex;gap:.5rem;align-items:center;font-weight:400}
</style></head><body><p><a href="/">← ${t("common.back")}</a></p><h1>${t("users.page.title")}</h1>""")
            error?.let { append("<p class=e role=alert>${t(it)}</p>") }
            users.forEach { u ->
                append("<div class=card><h2>${esc(u.name)}</h2>")
                if (u.owner) {
                    append("<p class=m>${t("users.page.owner")}</p>")
                } else {
                    append("<form method=post action=\"/plugins/users/update\"><input type=hidden name=id value=${u.id}>${rulesForm(u)}")
                    append("<label>${t("users.page.newPassword")}<input type=password name=password autocomplete=new-password minlength=8></label>")
                    append("<button>${t("common.save")}</button></form>")
                    append("<form method=post action=\"/plugins/users/remove\" style=\"margin-top:.5rem\"><input type=hidden name=id value=${u.id}><button class=ghost>${t("common.remove")}</button></form>")
                }
                append("</div>")
            }
            append("<div class=card><h2>${t("users.page.add")}</h2><form method=post action=\"/plugins/users/add\">")
            append("<label>${t("users.page.name")}<input type=text name=name required maxlength=64></label>")
            append("<label>${t("users.page.password")}<input type=password name=password required minlength=8 autocomplete=new-password></label>")
            append(rulesForm(null)).append("<button>${t("common.add")}</button></form></div>")
            append("<p class=m>${t("users.page.ratingsHint")}</p></body></html>")
        }
        response.headers.append("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'")
        respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
