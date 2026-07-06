package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.authenticatedApi
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
    // CYP-122: per-agent connector fidelity for the steady-state read model (Doc 10 §6.4). Null in the dev
    // install → capabilities stay null (UI shows nothing reduced).
    capabilitiesOf: (agentId: String) -> com.tneff.cyppieagents.model.Capabilities? = { null },
    // CYP-122: the agent's connector kind, single-sourced from the config registry so an opt-in is
    // reflected without mutating the hub Agent. Null → keep the Agent's own value (default STREAM_JSON).
    connectorKindOf: (agentId: String) -> com.tneff.cyppieagents.model.ConnectorKind? = { null },
    // CYP-137: the agent's provider (tool), from the boot ProviderRegistry. Null in the dev install →
    // provider stays null (fail-closed: the UI omits the qualifier, never guesses).
    providerOf: (agentId: String) -> com.tneff.cyppieagents.model.ProviderInfo? = { null },
    // CYP-178: the principal-resolution deps for the operator-gated write (PUT /api/acl). Defaults to the
    // token-only path (operator token authenticates; the Kratos human path is deny-all); installPlatform
    // passes the real AuthDeps so a verified human OPERATOR also authenticates.
    deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry),
) {
    route("/api") {
        get("/health") { call.respondText("ok") }

        // CC1 / CYP-179 — the roster is a comm READ, gated like its siblings (`/channels`, `/inbox`, `/acl`):
        // [requireCommReader] (agent / operator token OR a verified human OPERATOR/MEMBER session). This CLOSES
        // the former anonymous list — off-localhost it leaked the topology (ids, names, roles, worktree names,
        // connectorKind, provider) to any unauthenticated caller. Removed from the route-enum public allowlist,
        // so the enumeration meta-test now REQUIRES this to fail closed without a credential (structural teeth).
        // Read-only tier: a MEMBER session sees the roster (like it sees channels) but the controls below stay
        // operator-gated and the send path stays token-only. CYP-73: fill each agent's LIVE status.
        get("/agents") {
            call.requireCommReader(deps, registry)
            // S13 / CYP-102 — agents are STRUCTURALLY scoped to the active project (PO decision, Option A):
            // a single HubState holds exactly the active project's agents (per-project live hub/session
            // re-instancing is S17, gated on `HubState.rescope`/switch — which today only re-scopes the
            // ACL matrix, not the agent set). `Agent` carries no projectId, and in the one-hub model there
            // is no other-project agent in this list to leak — so there is no cross-project agent vector to
            // filter here and no separate mutation proof. Real per-project agent isolation lands in S17 when
            // the hub is instanced per project; THEN this list filters by the active project's hub.
            val agents = state.agents.map { agent ->
                agent.copy(
                    runState = lifecycle?.runStateOf(agent.id) ?: agent.runState,
                    // CYP-122: fill the steady-state connector fidelity + kind for the per-agent read model.
                    capabilities = capabilitiesOf(agent.id) ?: agent.capabilities,
                    connectorKind = connectorKindOf(agent.id) ?: agent.connectorKind,
                    // CYP-137: fill the provider (tool) so the UI can show it subordinate to identity.
                    provider = providerOf(agent.id) ?: agent.provider,
                )
            }
            call.respond(agents)
        }

        // Reads accept an agent, the operator, OR a verified human (OPERATOR=member-of-all, MEMBER=ACL-subject
        // by identityId, fail-closed) — SAME AclMatrix. The send (POST) stays token-only: a MEMBER can't post.
        get("/channels") {
            val participant = call.requireCommReader(deps, registry)
            call.respond(hub.readableChannels(participant))
        }

        route("/channels/{id}/messages") {
            get {
                val participant = call.requireCommReader(deps, registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                call.respond(hub.channelMessages(participant, channelId, since))
            }
            post {
                // CYP-188 P2b-iii: the WRITE gate admits a verified human session (like the read gate); the
                // per-channel `canWrite` authz stays at the postAsAgent chokepoint (deny-without-grant → 403,
                // allow-with-`canWrite:true`-grant → 201; uniform 403, no channel-existence tell). Send is
                // STILL the single write path — no WS-send bypasses this chokepoint.
                val participant = call.requireCommWriter(deps, registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val body = call.receive<SendMessageRequest>()
                MessageInput.requireValidBody(body.body) // CYP-143: cap + validate before the chokepoint
                // Sender = bearer identity; channel = path. Body carries neither (Gate #1).
                val message = hub.postAsAgent(participant, channelId, body.body, body.meta)
                call.respond(HttpStatusCode.Created, message)
            }
        }

        get("/inbox") {
            val participant = call.requireCommReader(deps, registry)
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            call.respond(hub.inbox(participant, since))
        }

        get("/acl") {
            val participant = call.requireCommReader(deps, registry)
            val filterChannel = call.request.queryParameters["channelId"]
            val filterAgent = call.request.queryParameters["agentId"]
            // Route through the project-scoped matrix (S12 / CYP-81), the SAME chokepoint as
            // canRead/canWrite/visibleMessages/inbox/WS — so GET /acl can't egress raw cross-project
            // entries. `state.acl.entries`/`isMember` are already filtered to the active project. A human
            // MEMBER (participant=identityId) sees only entries of channels they are a member of → fail-closed.
            val visible = if (participant == HubState.OPERATOR_ID) {
                state.acl.entries
            } else {
                state.acl.entries.filter { state.acl.isMember(it.channelId, participant) }
            }
            call.respond(
                visible.filter {
                    (filterChannel == null || it.channelId == filterChannel) &&
                        (filterAgent == null || it.agentId == filterAgent)
                },
            )
        }

        // CYP-178: the ACL write is gated STRUCTURALLY under the group — fail-closed BEFORE the body is
        // received. The RC1 route-enumeration meta-test is the net. (The reads above stay participant-gated.)
        authenticatedApi(deps, com.tneff.cyppieagents.auth.AuthRole.OPERATOR) {
            put("/acl") {
                val entry = call.receive<AclEntry>()
                call.respond(hub.setAcl(entry, by = HubState.OPERATOR_ID))
            }
        }
    }

    commSocket(hub, state, registry, deps)
}

/**
 * `/ws/comm` (Spec 02 §8): live push of messages / ACL changes / channel lists to a connected
 * participant — using the SAME [com.tneff.cyppieagents.model.AclMatrix] filter as REST (only
 * readable channels). Auth: agent OR operator token, via Bearer or `?token=` (browser). Idempotency
 * is by `message.id` on the client. An optional [Subscribe] narrows the stream (still ACL-filtered).
 */
fun Route.commSocket(hub: Hub, state: HubState, registry: TokenRegistry, deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry)) {
    webSocket("/ws/comm") {
        // CYP-188 B: read-tier (token OR verified human session) — same as GET /api/channels. A MEMBER session
        // maps to its identityId → the stream stays ACL-`canRead`-filtered (fail-closed empty until granted).
        val participant = call.wsReaderOrNull(deps, registry)
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
                        // CYP-255 ① — route through visibleMessages (canRead AND the ProjectScope gate),
                        // the SAME filter as the REST path (Hub.channelMessages), not canRead alone. A
                        // buffered MessageEvent from project A must NOT leak over a live /ws/comm connection
                        // that has since switched to B: canRead alone still passes an out-of-project channel
                        // the participant is a lingering member of; the project gate drops it fail-closed.
                        val visible = state.acl.visibleMessages(participant, listOf(event.message)).isNotEmpty()
                        if (visible && (subscribed?.contains(ch) != false)) event else null
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
