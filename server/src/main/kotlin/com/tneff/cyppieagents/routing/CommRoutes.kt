package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.Audit
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.BadRequestException as KtorBadRequestException

/**
 * Boot configuration for the comm hub. Tokens live here (server-side only) and are mapped to
 * agent ids; they are never part of any shared DTO (Spec 02 §14, security-by-structure).
 */
class CommConfig(
    val agents: List<Agent>,
    /** bearer token → agentId */
    val tokens: Map<String, String>,
    val operatorToken: String?,
    val store: MessageStore,
) {
    companion object {
        /** Dev defaults (PO + frontend + backend). Real tokens come from the host env / `.env`. */
        fun dev(): CommConfig {
            val agents = listOf(
                Agent("po", "Product Owner", Role.PO, "po"),
                Agent("frontend", "Frontend", Role.WORKER, "frontend"),
                Agent("backend", "Backend", Role.WORKER, "backend"),
            )
            val tokens = agents.associate { agent ->
                (System.getenv("HUB_TOKEN_${agent.id.uppercase()}") ?: "dev-token-${agent.id}") to agent.id
            }
            val operator = System.getenv("OPERATOR_TOKEN") ?: "dev-operator-token"
            return CommConfig(agents, tokens, operator, InMemoryMessageStore())
        }
    }
}

/**
 * Installs content negotiation, the uniform error envelope, and the comm REST routes. Returns the
 * [Hub] so callers (e.g. the mediator) can post on agents' behalf through the same enforcement.
 */
fun Application.installComm(config: CommConfig): Hub {
    val state = HubState.hubAndSpoke(config.agents)
    val audit = Audit()
    val hub = Hub(state, config.store, audit)
    val registry = TokenRegistry(config.tokens, config.operatorToken)

    install(ContentNegotiation) { json(CommJson) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message)))
        }
        exception<KtorBadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorBody(ApiError("bad_request", cause.message ?: "bad request")))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ApiErrorBody(ApiError("internal", "internal error")))
        }
    }
    routing { commRoutes(hub, state, registry, audit) }
    return hub
}

fun Route.commRoutes(hub: Hub, state: HubState, registry: TokenRegistry, audit: Audit) {
    route("/api") {
        get("/health") { call.respondText("ok") }

        // Agents carry no secrets (token is server-side only) — safe to return as-is.
        get("/agents") { call.respond(state.agents) }

        get("/channels") {
            val agentId = call.requireAgent(registry)
            call.respond(hub.readableChannels(agentId))
        }

        route("/channels/{id}/messages") {
            get {
                val agentId = call.requireAgent(registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                call.respond(hub.channelMessages(agentId, channelId, since))
            }
            post {
                val agentId = call.requireAgent(registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val body = call.receive<SendMessageRequest>()
                // Sender = bearer identity; channel = path. Body carries neither (Gate #1).
                val message = hub.postAsAgent(agentId, channelId, body.body, body.meta)
                call.respond(HttpStatusCode.Created, message)
            }
        }

        get("/inbox") {
            val agentId = call.requireAgent(registry)
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            call.respond(hub.inbox(agentId, since))
        }

        get("/acl") {
            val token = call.bearerToken()
            val filterChannel = call.request.queryParameters["channelId"]
            val filterAgent = call.request.queryParameters["agentId"]
            val visible = if (registry.isOperator(token)) {
                state.entries
            } else {
                val agentId = registry.agentFor(token) ?: throw UnauthorizedException()
                val myChannels = state.channels.filter { agentId in it.members }.map { it.id }.toSet()
                state.entries.filter { it.channelId in myChannels }
            }
            call.respond(
                visible.filter {
                    (filterChannel == null || it.channelId == filterChannel) &&
                        (filterAgent == null || it.agentId == filterAgent)
                },
            )
        }

        put("/acl") {
            call.requireOperator(registry)
            val entry = call.receive<AclEntry>()
            val saved = state.setAcl(entry)
            // Audit the most security-relevant mutation (R1).
            audit.aclChanged(saved.channelId, saved.agentId, saved.canRead, saved.canWrite, by = "operator")
            call.respond(saved)
        }
    }
}
