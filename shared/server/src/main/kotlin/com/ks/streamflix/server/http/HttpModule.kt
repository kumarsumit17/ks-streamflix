package com.ks.streamflix.server.http

import com.ks.streamflix.api.ApiError
import com.ks.streamflix.api.ApiV1
import com.ks.streamflix.api.Credentials
import com.ks.streamflix.api.ItemList
import com.ks.streamflix.api.LibraryList
import com.ks.streamflix.api.NewLibrary
import com.ks.streamflix.api.Session
import com.ks.streamflix.api.StreamLink
import com.ks.streamflix.api.User
import com.ks.streamflix.server.domain.DomainException
import com.ks.streamflix.server.plugin.Core
import com.ks.streamflix.server.plugin.SESSION_AUTH
import com.ks.streamflix.server.plugin.SESSION_COOKIE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** The signed-in user of a request, and the token it used. */
data class SessionPrincipal(val user: User, val token: String)

/** The token of a request: `Authorization: Bearer <token>`, or the browser pages' cookie. */
fun ApplicationCall.sessionToken(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.ifEmpty { null }
        ?: request.cookies[SESSION_COOKIE]?.ifEmpty { null }

/** Installs the core: JSON, errors, sign-in, byte ranges, and the API; then the plugins' routes. */
fun Application.coreModule(core: Core, pluginRoutes: List<Route.() -> Unit>) {
    install(ContentNegotiation) { json(ApiV1.json) }
    install(PartialContent)
    install(StatusPages) {
        exception<DomainException> { call, e -> call.respond(e.kind.status(), ApiError(e.code)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("api.error.badRequest")) }
    }
    install(Authentication) {
        provider(SESSION_AUTH) {
            authenticate { context ->
                val token = context.call.sessionToken()
                val user = token?.let { core.auth.userFor(it) }
                if (user != null) {
                    context.principal(SessionPrincipal(user, token))
                } else {
                    // "No credentials" also for an expired token, so pages with optional sign-in
                    // simply see no user; the API answers 401.
                    context.challenge(SESSION_AUTH, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                        call.respond(HttpStatusCode.Unauthorized, ApiError("auth.error.signIn"))
                        challenge.complete()
                    }
                }
            }
        }
    }
    routing {
        apiRoutes(core)
        pluginRoutes.forEach { it() }
    }
}

private fun DomainException.Kind.status() = when (this) {
    DomainException.Kind.BAD_REQUEST -> HttpStatusCode.BadRequest
    DomainException.Kind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    DomainException.Kind.FORBIDDEN -> HttpStatusCode.Forbidden
    DomainException.Kind.NOT_FOUND -> HttpStatusCode.NotFound
    DomainException.Kind.CONFLICT -> HttpStatusCode.Conflict
    DomainException.Kind.TOO_MANY -> HttpStatusCode.TooManyRequests
}

private fun ApplicationCall.user(): User = principal<SessionPrincipal>()!!.user

private fun ApplicationCall.requireOwner() {
    if (!user().owner) throw DomainException("auth.error.ownerOnly", DomainException.Kind.FORBIDDEN)
}

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw DomainException("common.error.notFound", DomainException.Kind.NOT_FOUND)

private fun Route.apiRoutes(core: Core) {
    get(ApiV1.INFO) { call.respond(core.info()) }

    post(ApiV1.SETUP) {
        val body = call.receive<Credentials>()
        core.auth.createOwner(body.name, body.password)
        val (token, user) = core.auth.signIn(body.name, body.password, call.request.origin.remoteAddress)
        call.respond(HttpStatusCode.Created, Session(token, user))
    }

    post(ApiV1.LOGIN) {
        val body = call.receive<Credentials>()
        val (token, user) = core.auth.signIn(body.name, body.password, call.request.origin.remoteAddress)
        call.respond(Session(token, user))
    }

    authenticate(SESSION_AUTH) {
        post(ApiV1.LOGOUT) {
            core.auth.signOut(call.principal<SessionPrincipal>()!!.token)
            call.respond(HttpStatusCode.NoContent)
        }

        route(ApiV1.LIBRARIES) {
            get { call.respond(LibraryList(core.libraries.libraries())) }
            post {
                call.requireOwner()
                val body = call.receive<NewLibrary>()
                call.respond(HttpStatusCode.Created, core.libraries.addLibrary(body.name, body.kind, body.folders))
            }
            get("{id}") { call.respond(core.libraries.library(call.longParam("id"))) }
            post("{id}/scan") {
                call.requireOwner()
                call.respond(core.libraries.scan(call.longParam("id")))
            }
        }

        route(ApiV1.ITEMS) {
            get {
                val library = call.request.queryParameters["library"]?.toLongOrNull()
                    ?: throw DomainException("library.error.notFound", DomainException.Kind.NOT_FOUND)
                call.respond(ItemList(core.libraries.items(library)))
            }
            get("{id}") { call.respond(core.libraries.item(call.longParam("id"))) }
            post("{id}/link") {
                val id = call.longParam("id")
                core.libraries.item(id)
                val (key, expires) = core.streamKeys.create(id)
                call.respond(StreamLink("${ApiV1.stream(id)}?key=$key", expires))
            }
        }
    }

    // A stream needs a session, or a stream link's key for this item (for players that cannot send the header).
    authenticate(SESSION_AUTH, optional = true) {
        get("${ApiV1.ITEMS}/{id}/stream") {
            val id = call.longParam("id")
            val key = call.request.queryParameters["key"]
            if (call.principal<SessionPrincipal>() == null && (key == null || !core.streamKeys.isValid(id, key))) {
                throw DomainException("auth.error.signIn", DomainException.Kind.UNAUTHORIZED)
            }
            val file = core.libraries.fileOf(id)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
            // Byte ranges (seeking) come from the PartialContent plugin.
            call.respond(LocalPathContent(file, contentTypeOf(file.fileName.toString())))
        }
    }
}

/** The MIME type of a video file, by its extension. */
fun contentTypeOf(fileName: String): ContentType = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> ContentType("video", "mp4")
    "mkv" -> ContentType("video", "x-matroska")
    "webm" -> ContentType("video", "webm")
    "mov" -> ContentType("video", "quicktime")
    "avi" -> ContentType("video", "x-msvideo")
    "ts", "m2ts", "mts" -> ContentType("video", "mp2t")
    "wmv" -> ContentType("video", "x-ms-wmv")
    "mpg", "mpeg" -> ContentType("video", "mpeg")
    "flv" -> ContentType("video", "x-flv")
    "ogv" -> ContentType("video", "ogg")
    "3gp" -> ContentType("video", "3gpp")
    else -> ContentType.Application.OctetStream
}
