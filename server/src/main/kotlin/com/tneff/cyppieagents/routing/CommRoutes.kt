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
import com.tneff.cyppieagents.model.MarkReadRequest
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.ProjectScope
import com.tneff.cyppieagents.model.ReadStateEvent
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
    // CYP-73: live per-agent run-state. CYP-255 (.4b): resolves the ACTIVE project's run-state per request
    // (was the boot LifecycleManager) — after a switch, GET /api/agents shows the switched-to project's
    // agents' states, not the boot project's. Default `{ null }` (dev install, no boot) → status stays default.
    runStateOf: (agentId: String) -> com.tneff.cyppieagents.model.AgentRunState? = { null },
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
    apiBase: String = "/api",
) {
    route(apiBase) {
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
                    runState = runStateOf(agent.id) ?: agent.runState,
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

        // CYP-273 — the per-viewer WRITABLE subset (the composer-enable seam). Same participant-read gate as
        // /channels (agent / operator / member / participant token all resolve); content-free (only channel ids
        // the caller already sees); computed via the send-enforcing AclMatrix.canWrite → single-source with the
        // POST. The client refreshes it on AclEvent for live enable/disable; the server-403 stays the authority.
        get("/channels/writable") {
            val participant = call.requireCommReader(deps, registry)
            call.respond(hub.writableChannels(participant))
        }

        route("/channels/{id}/messages") {
            get {
                val participant = call.requireCommReader(deps, registry)
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                // CYP-744: the frontend history carries the DeliveredMessage wrapper (spans) — same envelope as the WS
                // echo + the POST return, so the client's messagesByChannel slot holds ONE shape.
                call.respond(hub.deliveredMessages(participant, channelId, since))
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
                // CYP-744: return the SAME DeliveredMessage envelope as the WS echo + REST get (Dev5 consistency:
                // one shape in the messagesByChannel slot). The spans are viewer-independent (Display), resolved once.
                call.respond(HttpStatusCode.Created, hub.deliveredOf(message))
            }
        }

        get("/inbox") {
            val participant = call.requireCommReader(deps, registry)
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            call.respond(hub.inbox(participant, since))
        }

        // CYP-705 — unread-per-channel read-state. OPERATOR-tier (measurement DoD, a2-po 2026-07-19): a grep of
        // web-ts found NO read-state HTTP consumer wired — only the CommPanel/unreadModel render layer against a
        // `readState` prop (net/rest.ts has no /read-state or /read call site). The web-ts operator serve is the
        // sole consumer path, so this is fail-closed operator-only. The STRUCTURAL OPERATOR gate gives MEMBER→403,
        // agent→403, participant→401 (resolvePrincipal rejects participant tokens), operator→200, and rejects
        // BEFORE the handler (removing the earlier 500s from `requireCommReader` on a mutation — the CYP-690 seam).
        // Subject = the single operator (OPERATOR_ID) → one operator cursor. A future MEMBER-tier read-state
        // consumer widens this to MEMBER-allowed self-scoped (deferred — needs the self-scope tooth, PO1).
        authenticatedApi(deps, com.tneff.cyppieagents.auth.AuthRole.OPERATOR) {
            get("/read-state") {
                call.respond(hub.readState(HubState.OPERATOR_ID))
            }
            // Non-optimistic mark-read: advance the operator's cursor to max(existing, upToSeq); returns the
            // updated ChannelReadState (the client shows "read" only on this echo).
            post("/channels/{id}/read") {
                val channelId = call.parameters["id"] ?: throw BadRequestException("missing channel id")
                val req = call.receive<MarkReadRequest>()
                call.respond(hub.markRead(HubState.OPERATOR_ID, channelId, req.upToSeq))
            }
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
                // CYP-255 ① — the per-participant filter is a pure function over the LIVE [state] (read
                // fresh per event, so a live project switch on this held connection re-scopes it), extracted
                // so BOTH egress axes (MessageEvent body + AclEvent metadata) are project-scoped through one
                // chokepoint and are testable deterministically against a foreign-project event.
                val out = commEventForParticipant(event, participant, state, subscribed)
                if (out != null) emit(out)
            }
        }
        // CYP-705: the self-only read-state pump — forward a ReadStateEvent ONLY when it targets THIS
        // participant (the subject is matched server-side and never sent on the wire → content-free).
        val readStatePump = launch {
            hub.readStateEvents.collect { targeted ->
                if (targeted.subject == participant) emit(targeted.event)
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
            readStatePump.cancel() // CYP-705
        }
    }
}

/**
 * CYP-255 ① — the pure per-participant filter for the `/ws/comm` live pump, extracted from [commSocket] so
 * BOTH egress axes are project-scoped through ONE chokepoint and testable deterministically (no live
 * socket) against a foreign-project event. Returns the event to forward to [participant], or null to drop.
 * Reads the ACTIVE project from [state] on **every** call, so a buffered event stamped for project A is
 * dropped once the held connection's active project has switched to B (the live-rescope leak the money-
 * tooth exercises end-to-end).
 *
 *  - **MessageEvent** → `visibleMessages` (canRead AND the ProjectScope/CYP-93-share gate) — EXACTLY the
 *    REST `Hub.channelMessages` filter, not canRead alone: an out-of-project channel the participant is a
 *    lingering member of passes canRead but is dropped by the project gate, fail-closed.
 *  - **AclEvent** → `ProjectScope.permits(entry.projectId, active) AND canRead(entry.channelId)`. An
 *    [com.tneff.cyppieagents.model.AclEntry] carries its OWN `projectId` (the AclMatrix filters the static
 *    entries list the same way, AclMatrix.kt:45), so it is scoped by that field — NOT `visibleMessages`,
 *    which is Message-shaped. Same leak class as the message body, lower severity (ACL metadata): who may
 *    read/write project A's channel must not surface over a connection since switched to B on an id
 *    collision. (Before this pass the branch was canRead-only; the old "same ACL filter as messages"
 *    comment was wrong — messages funnel through `visibleMessages`, an AclEvent through this pair.)
 *  - **ChannelsEvent** → re-scoped to the channels [participant] may currently read.
 */
internal fun commEventForParticipant(
    event: CommWsServerEvent,
    participant: String,
    state: HubState,
    subscribed: Set<String>?,
): CommWsServerEvent? = when (event) {
    is MessageEvent -> {
        // CYP-744: the frame now carries a DeliveredMessage wrapper; filter on the whole message inside it.
        val ch = event.delivered.message.channelId
        val visible = state.acl.visibleMessages(participant, listOf(event.delivered.message)).isNotEmpty()
        if (visible && (subscribed?.contains(ch) != false)) event else null
    }
    is AclEvent ->
        if (ProjectScope.permits(event.entry.projectId, state.activeProjectId) &&
            state.acl.canRead(event.entry.channelId, participant)
        ) {
            event
        } else {
            null
        }
    is ChannelsEvent -> ChannelsEvent(state.acl.readableChannels(participant)) // re-scope to participant
    // CYP-705: ReadStateEvent is per-recipient and NEVER broadcast through this content-filter path — it is
    // routed self-only via the Hub's targeted read-state flow (see commSocket). So it can't arrive here.
    is ReadStateEvent -> null
}
