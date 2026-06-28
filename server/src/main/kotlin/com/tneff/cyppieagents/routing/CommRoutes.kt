package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.model.Subscribe
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
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
 * Installs content negotiation, websockets, the uniform error envelope, and the comm REST + WS
 * routes. Returns the [Hub] so callers (e.g. the mediator) can post on agents' behalf through the
 * same enforcement.
 */
fun Application.installComm(config: CommConfig): Hub {
    // Operator participates in the ACL (member of every channel) when an operator token exists.
    val operatorId = if (config.operatorToken != null) HubState.OPERATOR_ID else null
    val state = HubState.hubAndSpoke(config.agents, operatorId)
    val hub = Hub(state, config.store)
    val registry = TokenRegistry(config.tokens, config.operatorToken)

    install(ContentNegotiation) { json(CommJson) }
    install(WebSockets)
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
    routing { commRoutes(hub, state, registry) }
    return hub
}

fun Route.commRoutes(
    hub: Hub,
    state: HubState,
    registry: TokenRegistry,
    // CYP-73: live process status. Null in the dev install (no boot) → status stays the default RUNNING.
    lifecycle: com.tneff.cyppieagents.boot.LifecycleManager? = null,
) {
    route("/api") {
        get("/health") { call.respondText("ok") }

        // Agents carry no secrets (token is server-side only) — public list. CYP-73: fill each agent's
        // LIVE status (RUNNING/STOPPED/ERROR). Status display is NOT operator-gated (same as this route);
        // only the controls below are operator-gated.
        get("/agents") {
            val agents = state.agents.map { agent ->
                lifecycle?.runStateOf(agent.id)?.let { agent.copy(runState = it) } ?: agent
            }
            call.respond(agents)
        }

        // Read/send accept an agent OR the operator (privileged participant) — same AclMatrix.
        get("/channels") {
            val participant = call.requireParticipant(registry)
            call.respond(hub.readableChannels(participant))
        }

        route("/channels/{id}/messages") {
            get {
                val participant = call.requireParticipant(registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                call.respond(hub.channelMessages(participant, channelId, since))
            }
            post {
                val participant = call.requireParticipant(registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val body = call.receive<SendMessageRequest>()
                // Sender = bearer identity; channel = path. Body carries neither (Gate #1).
                val message = hub.postAsAgent(participant, channelId, body.body, body.meta)
                call.respond(HttpStatusCode.Created, message)
            }
        }

        get("/inbox") {
            val participant = call.requireParticipant(registry)
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            call.respond(hub.inbox(participant, since))
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
            call.respond(hub.setAcl(entry, by = HubState.OPERATOR_ID))
        }
    }

    commSocket(hub, state, registry)
}

/**
 * `/ws/comm` (Spec 02 §8): live push of messages / ACL changes / channel lists to a connected
 * participant — using the SAME [com.tneff.cyppieagents.model.AclMatrix] filter as REST (only
 * readable channels). Auth: agent OR operator token, via Bearer or `?token=` (browser). Idempotency
 * is by `message.id` on the client. An optional [Subscribe] narrows the stream (still ACL-filtered).
 */
fun Route.commSocket(hub: Hub, state: HubState, registry: TokenRegistry) {
    webSocket("/ws/comm") {
        val token = call.bearerToken() ?: call.request.queryParameters["token"]
        val participant = registry.participantFor(token)
        if (participant == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        var subscribed: Set<String>? = null // null = all readable channels

        suspend fun emit(event: CommWsServerEvent) =
            send(Frame.Text(CommJson.encodeToString(CommWsServerEvent.serializer(), event)))

        // Initial snapshot: the channels this participant may read.
        emit(ChannelsEvent(hub.readableChannels(participant)))

        val pump = launch {
            hub.events.collect { event ->
                val out: CommWsServerEvent? = when (event) {
                    is MessageEvent -> {
                        val ch = event.message.channelId
                        if (state.acl.canRead(ch, participant) && (subscribed?.contains(ch) != false)) event else null
                    }
                    // Same ACL filter as messages: an ACL change is metadata about a channel, so only
                    // a participant who can read that channel may learn of it (no cross-channel leak).
                    is AclEvent -> if (state.acl.canRead(event.entry.channelId, participant)) event else null
                    is ChannelsEvent -> ChannelsEvent(hub.readableChannels(participant)) // re-scope to participant
                }
                if (out != null) emit(out)
            }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val client = runCatching { CommJson.decodeFromString<CommWsClientEvent>(frame.readText()) }.getOrNull()
                    if (client is Subscribe) subscribed = client.channelIds.toSet()
                }
            }
        } finally {
            pump.cancel()
        }
    }
}
