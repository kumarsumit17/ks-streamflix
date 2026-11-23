package com.ks.streamflix.plugins.web

import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Item
import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.api.User
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.http.SessionPrincipal
import com.ks.streamflix.server.lang.Language
import com.ks.streamflix.server.lang.Strings
import com.ks.streamflix.server.plugin.Core
import com.ks.streamflix.server.plugin.LANG_COOKIE
import com.ks.streamflix.server.plugin.PluginContext
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.SESSION_COOKIE
import com.ks.streamflix.server.plugin.ServerPlugin
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Plugin `web`: setup, sign-in, libraries, browsing and playing in a browser. */
class WebPlugin : ServerPlugin {
    override val id = "web"

    override fun install(context: PluginContext) {
        val pages = Pages(context.core, context.strings)
        context.routes {
            authenticate(SESSION_AUTH, optional = true) { pages.routes(this) }
        }
    }
}


private class Pages(private val core: Core, private val strings: Strings) {

    private fun ApplicationCall.lang() = Language.resolve(request.cookies[LANG_COOKIE], request.headers[HttpHeaders.AcceptLanguage])
    private fun ApplicationCall.user(): User? = principal<SessionPrincipal>()?.user

    fun routes(route: Route) = with(route) {
        get("/") {
            when {
                core.auth.setupNeeded() -> call.respondRedirect("/setup")
                call.user() == null -> call.respondRedirect("/login")
                else -> call.home(null, null)
            }
        }

        get("/setup") {
            if (!core.auth.setupNeeded()) return@get call.respondRedirect("/")
            call.credentialsPage(setup = true, error = null)
        }
        post("/setup") {
            val form = call.form() ?: return@post
            try {
                core.auth.createOwner(form["name"].orEmpty(), form["password"].orEmpty())
                call.signIn(form)
            } catch (e: DomainException) {
                call.credentialsPage(setup = true, error = e.code)
            }
        }

        get("/login") { call.credentialsPage(setup = false, error = null) }
        post("/login") {
            val form = call.form() ?: return@post
            try {
                call.signIn(form)
            } catch (e: DomainException) {
                call.credentialsPage(setup = false, error = e.code, status = HttpStatusCode.Unauthorized)
            }
        }
        post("/logout") {
            call.form() ?: return@post
            call.principal<SessionPrincipal>()?.let { core.auth.signOut(it.token) }
            call.response.cookies.append(Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/", httpOnly = true))
            call.respondRedirect("/login")
        }

        get("/lang") {
            val choice = call.request.queryParameters["set"].takeIf { Language.ofCode(it) != null } ?: ""
            call.response.cookies.append(
                Cookie(LANG_COOKIE, choice, maxAge = if (choice.isEmpty()) 0 else 60 * 60 * 24 * 365, path = "/", extensions = mapOf("SameSite" to "Lax")),
            )
            val back = call.request.queryParameters["back"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
            call.respondRedirect(back)
        }

        post("/libraries") {
            val user = call.signedIn() ?: return@post
            val form = call.form() ?: return@post
            try {
                if (!user.owner) throw DomainException("auth.error.ownerOnly", DomainException.Kind.FORBIDDEN)
                val kind = if (form["kind"] == "shows") LibraryKind.SHOWS else LibraryKind.MOVIES
                val library = core.libraries.addLibrary(form["name"].orEmpty(), kind, listOf(form["folder"].orEmpty()))
                val result = core.libraries.scan(library.id)
                call.home(null, scannedText(call.lang(), result))
            } catch (e: DomainException) {
                call.home(e.code, null)
            }
        }
        post("/libraries/{id}/scan") {
            val user = call.signedIn() ?: return@post
            call.form() ?: return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.home("library.error.notFound", null)
            try {
                if (!user.owner) throw DomainException("auth.error.ownerOnly", DomainException.Kind.FORBIDDEN)
                call.home(null, scannedText(call.lang(), core.libraries.scan(id)))
            } catch (e: DomainException) {
                call.home(e.code, null)
            }
        }

        get("/library/{id}") {
            call.signedIn() ?: return@get
            val id = call.parameters["id"]?.toLongOrNull()
            try {
                val library = core.libraries.library(id ?: -1)
                val items = core.libraries.items(library.id)
                val lang = call.lang()
                call.page(library.name) {
                    append("<p><a href=\"/\">← ").esc(t(lang, "web.libraries.heading")).append("</a></p>")
                    append("<h1>").esc(library.name).append("</h1>")
                    if (items.isEmpty()) append("<p class=muted>").esc(t(lang, "web.library.empty")).append("</p>")
                    if (library.kind == LibraryKind.SHOWS) {
                        items.groupBy { it.series ?: it.title }.forEach { (series, episodes) ->
                            append("<h2>").esc(series).append("</h2><ul class=list>")
                            episodes.forEach { itemRow(lang, it) }
                            append("</ul>")
                        }
                    } else {
                        append("<ul class=list>")
                        items.forEach { itemRow(lang, it) }
                        append("</ul>")
                    }
                }
            } catch (e: DomainException) {
                call.home(e.code, null)
            }
        }

        get("/watch/{id}") {
            call.signedIn() ?: return@get
            try {
                val item = core.libraries.item(call.parameters["id"]?.toLongOrNull() ?: -1)
                val lang = call.lang()
                val src = ApiV1.stream(item.id)
                call.page(displayTitle(lang, item)) {
                    append("<p><a href=\"/library/${item.libraryId}\">← ").esc(t(lang, "web.libraries.heading")).append("</a></p>")
                    append("<h1>").esc(displayTitle(lang, item)).append("</h1>")
                    append("<video controls autoplay preload=metadata src=\"$src\" ")
                    append("onerror=\"document.getElementById('unsupported').hidden=false\"></video>")
                    append("<p id=unsupported class=error hidden>").esc(t(lang, "web.watch.unsupported")).append("</p>")
                    append("<p class=muted><a href=\"$src\">").esc(t(lang, "web.watch.direct")).append("</a></p>")
                    append("<p id=plot></p>")
                    // Details and subtitles come from their plugins' APIs, when the server has them.
                    append("<script>(async()=>{const id=${item.id},v=document.querySelector('video');")
                    append("try{const d=await (await fetch('/api/v1/items/'+id+'/details')).json();if(d.plot)document.getElementById('plot').textContent=d.plot}catch(e){}")
                    append("try{const r=await fetch('/api/v1/plugins/subtitles/items/'+id);if(r.ok){(await r.json()).tracks.forEach((t,i)=>{const e=document.createElement('track');")
                    append("e.kind='subtitles';e.label=t.label;if(t.language)e.srclang=t.language;e.src=t.path;if(t.default)e.default=true;v.appendChild(e)})}}catch(e){}})()</script>")
                }
            } catch (e: DomainException) {
                call.home(e.code, null)
            }
        }
    }

    // ---- Pages -----------------------------------------------------------------------------------

    private suspend fun ApplicationCall.home(error: String?, notice: String?) {
        val user = user() ?: return respondRedirect("/login")
        val lang = lang()
        val libraries = core.libraries.libraries()
        page(t(lang, "web.libraries.heading")) {
            append("<h1>").esc(t(lang, "web.libraries.heading")).append("</h1>")
            error?.let { append("<p class=error role=alert>").esc(t(lang, it)).append("</p>") }
            notice?.let { append("<p class=notice role=status>").esc(it).append("</p>") }
            if (libraries.isEmpty()) append("<p class=muted>").esc(t(lang, "web.libraries.empty")).append("</p>")
            append("<ul class=cards>")
            libraries.forEach { library ->
                append("<li class=card><a class=title href=\"/library/${library.id}\">").esc(library.name).append("</a>")
                append("<span class=muted>").esc(t(lang, "web.libraries.items", mapOf("count" to library.itemCount))).append("</span>")
                append("<span class=muted>").esc(library.folders.joinToString(", ")).append("</span>")
                if (user.owner) {
                    append("<form method=post action=\"/libraries/${library.id}/scan\"><button class=secondary>")
                        .esc(t(lang, "web.library.scan")).append("</button></form>")
                }
                append("</li>")
            }
            append("</ul>")
            if (user.owner && core.adminPages.isNotEmpty()) {
                append("<h2>").esc(t(lang, "common.settings")).append("</h2><ul class=list>")
                core.adminPages.forEach { (label, path) -> append("<li><a href=\"").esc(path).append("\">").esc(t(lang, label)).append("</a></li>") }
                append("</ul>")
            }
            if (user.owner) {
                val example = if (System.getProperty("os.name").startsWith("Windows")) "D:\\Films" else "/Users/me/Movies"
                append("<h2>").esc(t(lang, "web.library.add")).append("</h2><form method=post action=\"/libraries\" class=stack>")
                field(t(lang, "web.library.name"), "<input name=name required maxlength=100>")
                field(
                    t(lang, "web.library.kind"),
                    "<select name=kind><option value=movies>${escaped(t(lang, "web.library.kind.movies"))}</option>" +
                        "<option value=shows>${escaped(t(lang, "web.library.kind.shows"))}</option></select>",
                )
                field(t(lang, "web.library.folder"), "<input name=folder required placeholder=\"${escaped(example)}\">")
                append("<p class=muted>").esc(t(lang, "web.library.folderHint", mapOf("example" to example))).append("</p>")
                append("<button>").esc(t(lang, "common.add")).append("</button></form>")
            }
        }
    }

    private suspend fun ApplicationCall.credentialsPage(setup: Boolean, error: String?, status: HttpStatusCode = HttpStatusCode.OK) {
        val lang = lang()
        val heading = t(lang, if (setup) "web.setup.heading" else "web.login.heading")
        page(heading, status, signedIn = false) {
            append("<div class=narrow><h1>").esc(heading).append("</h1>")
            if (setup) append("<p class=muted>").esc(t(lang, "web.setup.intro")).append("</p>")
            error?.let { append("<p class=error role=alert>").esc(t(lang, it)).append("</p>") }
            append("<form method=post action=\"${if (setup) "/setup" else "/login"}\" class=stack>")
            field(t(lang, "web.field.name"), "<input name=name required maxlength=64 autocomplete=username autofocus>")
            field(
                t(lang, "web.field.password"),
                "<input name=password type=password required ${if (setup) "minlength=8 autocomplete=new-password" else "autocomplete=current-password"}>",
            )
            append("<button>").esc(t(lang, if (setup) "web.setup.submit" else "web.login.submit")).append("</button></form></div>")
        }
    }

    private suspend fun ApplicationCall.page(
        title: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        signedIn: Boolean = true,
        body: StringBuilder.() -> Unit,
    ) {
        val lang = lang()
        val html = StringBuilder()
        html.append("<!doctype html><html lang=${lang.code}><head><meta charset=utf-8>")
            .append("<meta name=viewport content=\"width=device-width, initial-scale=1\">")
            .append("<title>").esc(title).append(" · KS StreamFlix</title><style>").append(CSS).append("</style></head><body>")
            .append("<header><a class=brand href=\"/\">KS StreamFlix</a><nav>")
        val back = request.local.uri.takeIf { it.startsWith("/") } ?: "/"
        val choice = request.cookies[LANG_COOKIE].orEmpty()
        listOf("" to "common.language.system", "en" to "common.language.english", "hi" to "common.language.hindi").forEach { (code, key) ->
            val current = if (code == choice) " aria-current=true" else ""
            html.append("<a class=lang href=\"/lang?set=$code&amp;back=${java.net.URLEncoder.encode(back, Charsets.UTF_8)}\"$current>")
                .esc(t(lang, key)).append("</a>")
        }
        if (signedIn && user() != null) {
            html.append("<form method=post action=\"/logout\"><button class=ghost>").esc(t(lang, "web.signOut")).append("</button></form>")
        }
        html.append("</nav></header><main>")
        html.body()
        html.append("</main></body></html>")
        response.header("X-Content-Type-Options", "nosniff")
        response.header("Content-Security-Policy", "default-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; media-src 'self'; connect-src 'self'")
        respondText(html.toString(), ContentType.Text.Html.withParameter("charset", "utf-8"), status)
    }

    private fun StringBuilder.field(label: String, input: String) {
        append("<label><span>").esc(label).append("</span>").append(input).append("</label>")
    }

    private fun StringBuilder.itemRow(lang: Language, item: Item) {
        // The poster, when a metadata plugin has one (the image is hidden when there is none).
        append("<li><a class=item href=\"/watch/${item.id}\"><img class=thumb alt=\"\" loading=lazy src=\"/api/v1/items/${item.id}/image/poster?width=160\" onerror=\"this.remove()\">")
            .esc(displayTitle(lang, item)).append("</a>")
        append("<span class=muted>").esc(item.container.uppercase()).append("</span></li>")
    }

    private fun displayTitle(lang: Language, item: Item): String = when (item.kind) {
        ItemKind.MOVIE -> item.year?.let { "${item.title} ($it)" } ?: item.title
        ItemKind.EPISODE -> {
            val code = t(lang, "web.episode", mapOf("season" to (item.season ?: 0).toString(), "episode" to (item.episode ?: 0).toString()))
            "$code · ${item.title}"
        }
    }

    private fun scannedText(lang: Language, r: com.ks.streamflix.api.ScanResult): String {
        val text = t(lang, "web.library.scanned", mapOf("added" to r.added, "updated" to r.updated, "removed" to r.removed))
        return if (r.missingFolders.isEmpty()) text
        else text + " " + t(lang, "web.library.missingFolders", mapOf("folders" to r.missingFolders.joinToString(", ")))
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun t(lang: Language, key: String, params: Map<String, Any> = emptyMap()) = strings.get(lang, key, params)

    private suspend fun ApplicationCall.signedIn(): User? = user() ?: run { respondRedirect("/login"); null }

    /**
     * The posted form, or null after refusing a post from another site: the session cookie is
     * SameSite=Strict, and the Origin header, when sent, must be this server.
     */
    private suspend fun ApplicationCall.form(): Parameters? {
        val origin = request.headers[HttpHeaders.Origin]
        if (origin != null && origin != "null" && java.net.URI(origin).host != request.host()) {
            respondText("", status = HttpStatusCode.Forbidden)
            return null
        }
        return receiveParameters()
    }

    private suspend fun ApplicationCall.signIn(form: Parameters) {
        val (token, _) = core.auth.signIn(form["name"].orEmpty(), form["password"].orEmpty(), request.origin.remoteAddress)
        response.cookies.append(
            Cookie(
                SESSION_COOKIE, token, path = "/", httpOnly = true, maxAge = 30 * 24 * 60 * 60,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        respondRedirect("/")
    }
}

private fun escaped(s: String): String = StringBuilder().esc(s).toString()

private fun StringBuilder.esc(s: String): StringBuilder {
    s.forEach {
        when (it) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
    return this
}

/** In the style of shadcn/ui (MIT): neutral zinc colours, light and dark, thin borders, clear focus. */
private val CSS = """
:root{--bg:#fff;--fg:#09090b;--muted:#71717a;--card:#fff;--border:#e4e4e7;--primary:#18181b;--primary-fg:#fafafa;
--secondary:#f4f4f5;--ring:#a1a1aa;--error:#dc2626;--notice:#16a34a;--radius:.625rem;color-scheme:light dark}
@media(prefers-color-scheme:dark){:root{--bg:#09090b;--fg:#fafafa;--muted:#a1a1aa;--card:#18181b;--border:#27272a;
--primary:#fafafa;--primary-fg:#18181b;--secondary:#27272a;--ring:#71717a;--error:#f87171;--notice:#4ade80}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",
"Noto Sans Devanagari",sans-serif}a{color:inherit}header{display:flex;align-items:center;justify-content:space-between;gap:1rem;
padding:.75rem 1.5rem;border-bottom:1px solid var(--border)}nav{display:flex;align-items:center;gap:.25rem;flex-wrap:wrap}
.brand{font-weight:600;text-decoration:none}.lang{padding:.25rem .5rem;border-radius:calc(var(--radius) - 4px);text-decoration:none;
color:var(--muted);font-size:13px}.lang[aria-current]{color:var(--fg);background:var(--secondary)}main{max-width:960px;margin:0 auto;
padding:1.5rem}h1{font-size:1.6rem;letter-spacing:-.02em;margin:.5rem 0 1rem}h2{font-size:1.1rem;margin:1.75rem 0 .75rem}
.muted{color:var(--muted);font-size:13px}.error{color:var(--error)}.notice{color:var(--notice)}.narrow{max-width:380px;margin:3rem auto}
.stack{display:grid;gap:.9rem;max-width:420px}label{display:grid;gap:.35rem;font-size:14px;font-weight:500}
input,select{height:2.25rem;padding:0 .75rem;border:1px solid var(--border);border-radius:calc(var(--radius) - 2px);
background:transparent;color:inherit;font:inherit}button{height:2.25rem;padding:0 1rem;border:0;border-radius:calc(var(--radius) - 2px);
background:var(--primary);color:var(--primary-fg);font:inherit;font-weight:500;cursor:pointer}button.secondary{background:var(--secondary);
color:var(--fg)}button.ghost{background:transparent;color:var(--fg)}button:hover{opacity:.9}
:focus-visible{outline:2px solid var(--ring);outline-offset:2px}.cards{list-style:none;padding:0;display:grid;gap:1rem;
grid-template-columns:repeat(auto-fill,minmax(240px,1fr))}.card{display:grid;gap:.35rem;padding:1rem;border:1px solid var(--border);
border-radius:var(--radius);background:var(--card);box-shadow:0 1px 2px rgb(0 0 0/.05)}.card .title{font-weight:600;text-decoration:none}
.card form{margin-top:.5rem}.list{list-style:none;padding:0;margin:0;border:1px solid var(--border);border-radius:var(--radius)}
.list li{display:flex;justify-content:space-between;gap:1rem;padding:.6rem 1rem;border-bottom:1px solid var(--border)}
.list li:last-child{border-bottom:0}.item{display:flex;align-items:center;gap:.75rem}.thumb{width:40px;height:60px;object-fit:cover;border-radius:4px}.list a{text-decoration:none}.list a:hover{text-decoration:underline}
video{width:100%;max-height:70vh;background:#000;border-radius:var(--radius)}
""".trimIndent().replace("\n", "")
