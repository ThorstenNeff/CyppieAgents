package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.auth.OperatorAudit
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelReadState
import com.tneff.cyppieagents.model.MarkReadRequest
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ClaudeMdUpdate
import com.tneff.cyppieagents.model.ClaudeMdView
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.AuthMe
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.model.ConnectorChoice
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import com.tneff.cyppieagents.model.WorkspaceMember
import com.tneff.cyppieagents.routing.ChangeEmailRequest
import com.tneff.cyppieagents.routing.ChangePasswordRequest
import com.tneff.cyppieagents.routing.MintParticipantTokenRequest
import com.tneff.cyppieagents.routing.MintedParticipantToken
import com.tneff.cyppieagents.routing.ParticipantTokenSummary
import com.tneff.cyppieagents.routing.RegisterRequest
import com.tneff.cyppieagents.routing.RevokedParticipantTokens
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer

/**
 * CYP-234a-2a — the **hand-authored REST-contract table**, verified against the REAL routing (the drift-test
 * [RestContractDriftTest] enforces `REST_OPS` path+method == the live `/api` enumeration, both directions). This
 * is the ONE place a human declares "what the frontend REST surface IS": path, method, auth tier, request +
 * response body. [ContractGenerator.openApi] renders it to OpenAPI 3.1; the drift-test guarantees it can't
 * silently diverge from the code.
 *
 * Scope = FRONTEND transport only. `/mcp/hub` (in-process Hub-MCP for Connector-A agents) is the connector
 * contract, not here → [EXCLUDED_API_PATHS] (asserted a KNOWN exclusion, not silently-missing). `/api/admin/db/
 * metrics` is defined but NOT wired into `PlatformWiring` on this tip → intentionally absent (the derivation
 * confirmed it is not a live route).
 */
object RestContract {

    /** Auth posture RECORDED per op (the CURRENT audited gate; 234b unifies enforcement, 234a documents it). */
    enum class Tier { PUBLIC, PARTICIPANT, PARTICIPANT_WRITE, MEMBER, OPERATOR }

    /** A request/response body shape. `Json`/`JsonArray` carry a descriptor the walker renders to a `$ref`. */
    sealed interface Body {
        data object None : Body
        data object Text : Body
        data object BinaryPng : Body
        data object Multipart : Body
        data class Json(val descriptor: SerialDescriptor) : Body
        data class JsonArray(val element: SerialDescriptor) : Body
    }

    data class Op(
        val method: String,
        val path: String,
        val tier: Tier,
        val request: Body = Body.None,
        val response: Body = Body.None,
    )

    /** `/api` paths intentionally NOT in the frontend OpenAPI (asserted KNOWN exclusions by the drift-test). */
    val EXCLUDED_API_PATHS: Set<String> = setOf("/mcp/hub")

    private inline fun <reified T> json() = Body.Json(serializer<T>().descriptor)
    private inline fun <reified T> arr() = Body.JsonArray(serializer<T>().descriptor)

    val REST_OPS: List<Op> = listOf(
        // --- CommRoutes (/api) ---
        Op("GET", "/api/health", Tier.PUBLIC, response = Body.Text),
        Op("GET", "/api/agents", Tier.PARTICIPANT, response = arr<Agent>()),
        Op("GET", "/api/channels", Tier.PARTICIPANT, response = arr<Channel>()),
        Op("GET", "/api/channels/writable", Tier.PARTICIPANT, response = arr<String>()), // CYP-273: composer-enable seam
        Op("GET", "/api/channels/{id}/messages", Tier.PARTICIPANT, response = arr<Message>()),
        Op("POST", "/api/channels/{id}/messages", Tier.PARTICIPANT_WRITE, request = json<SendMessageRequest>(), response = json<Message>()),
        Op("GET", "/api/inbox", Tier.PARTICIPANT, response = arr<Message>()),
        // CYP-705 — unread-per-channel read-state. OPERATOR-tier (measurement DoD: no MEMBER-tier read-state
        // consumer exists in web-ts → fail-closed operator-only; the operator serve is the sole consumer).
        // GET returns one entry per channel the operator has a cursor for (absence ⇒ UNKNOWN); POST advances it.
        Op("GET", "/api/read-state", Tier.OPERATOR, response = arr<ChannelReadState>()),
        Op("POST", "/api/channels/{id}/read", Tier.OPERATOR, request = json<MarkReadRequest>(), response = json<ChannelReadState>()),
        Op("GET", "/api/acl", Tier.PARTICIPANT, response = arr<AclEntry>()),
        Op("PUT", "/api/acl", Tier.OPERATOR, request = json<AclEntry>(), response = json<AclEntry>()),
        // --- AgentMgmtRoutes (/api/agents) ---
        Op("GET", "/api/agents/{id}", Tier.PARTICIPANT, response = json<AgentDetail>()),
        Op("GET", "/api/agents/{id}/avatar", Tier.PARTICIPANT, response = Body.BinaryPng),
        Op("GET", "/api/agents/{id}/avatar/preview", Tier.PARTICIPANT, response = Body.BinaryPng),
        Op("POST", "/api/agents", Tier.OPERATOR, request = json<NewAgentSpec>(), response = json<CreatedAgent>()),
        Op("PUT", "/api/agents/{id}", Tier.OPERATOR, request = json<AgentEdit>(), response = json<Agent>()),
        Op("DELETE", "/api/agents/{id}", Tier.OPERATOR, response = Body.None),
        Op("POST", "/api/agents/{id}/avatar", Tier.OPERATOR, request = Body.Multipart, response = json<AgentDetail>()),
        Op("DELETE", "/api/agents/{id}/avatar", Tier.OPERATOR, response = Body.None),
        // --- CYP-310 CLAUDE.md management (LOCAL) ---
        Op("GET", "/api/agents/{id}/claude-md", Tier.PARTICIPANT, response = json<ClaudeMdView>()),
        Op("POST", "/api/agents/{id}/claude-md", Tier.OPERATOR, request = json<ClaudeMdUpdate>(), response = json<ClaudeMdView>()),
        // --- LifecycleRoutes (/api/agents/{id}) ---
        Op("POST", "/api/agents/{id}/stop", Tier.OPERATOR, response = json<AgentRunStateEvent>()),
        Op("POST", "/api/agents/{id}/start", Tier.OPERATOR, response = json<AgentRunStateEvent>()),
        Op("POST", "/api/agents/{id}/restart", Tier.OPERATOR, response = json<AgentRunStateEvent>()),
        // --- TerminalGrantRoutes (CYP-421 c: operator-only per-agent terminal-delegation; MOUNTED but INERT unless CYPPIE_TERMINAL_DELEGATION_ENABLED) ---
        Op("GET", "/api/agents/{id}/terminal-grants", Tier.OPERATOR, response = json<com.tneff.cyppieagents.model.TerminalGrants>()),
        Op("PUT", "/api/agents/{id}/terminal-grants", Tier.OPERATOR, request = json<com.tneff.cyppieagents.model.TerminalGrantRequest>(), response = json<com.tneff.cyppieagents.model.TerminalGrants>()),
        Op("DELETE", "/api/agents/{id}/terminal-grants", Tier.OPERATOR, request = json<com.tneff.cyppieagents.model.TerminalGrantRequest>(), response = json<com.tneff.cyppieagents.model.TerminalGrants>()),
        // CYP-355 (BE-2): the hand-off trigger (mediated ↔ interactive). REJECTED is a 200 body (non-optimistic).
        Op("POST", "/api/agents/{id}/mode", Tier.OPERATOR, request = json<com.tneff.cyppieagents.model.ModeChangeRequest>(), response = json<com.tneff.cyppieagents.model.ModeChangeResponse>()),
        // --- RendezvousRoutes (CYP-507, Phase-2 activation) — CP resolve/register of a hub's OPAQUE rendezvous.
        //     INERT (typed failure) until CYPPIE_REMOTE_RELAY_URL; business outcome = 200 + typed body (mint idiom). ---
        Op("GET", "/api/cp/rendezvous/{hubId}", Tier.OPERATOR, response = json<com.tneff.cyppieagents.controlplane.RendezvousResolveResponse>()),
        Op("POST", "/api/cp/rendezvous/{hubId}", Tier.OPERATOR, response = json<com.tneff.cyppieagents.controlplane.RendezvousResolveResponse>()),
        // --- HubTicketRoutes (CYP-508, Phase-2 activation) — CP mint of the hub hubTicket (operator-gated).
        //     INERT (NOT_AUTHORIZED_FOR_HUB) until the §3 swap gate; business outcome = 200 + typed body. ---
        Op("POST", "/api/cp/hubticket", Tier.OPERATOR, request = json<com.tneff.cyppieagents.controlplane.HubTicketRequest>(), response = json<com.tneff.cyppieagents.controlplane.HubTicketResponse>()),
        // --- HubAdmissionRoutes (CYP-512, Phase-2 activation) — live hub admission into the CP (operator-authed;
        //     ownerId bound to the authenticated operator; nonce single-use). Empty registry → fail-closed. ---
        Op("GET", "/api/cp/challenge", Tier.OPERATOR, response = json<com.tneff.cyppieagents.controlplane.HubChallenge>()),
        Op("POST", "/api/cp/admit", Tier.OPERATOR, request = json<com.tneff.cyppieagents.controlplane.HubAdmissionRequest>(), response = json<com.tneff.cyppieagents.controlplane.HubAdmissionResult>()),
        // --- HubDiscoveryRoutes (CYP-530, S-J) — operator-scoped hub list (the GUI's hub picker). Owner-filtered over
        //     the CP registry the CYP-512 admission populates; INERT-safe (empty until a hub self-admits). ---
        Op("GET", "/api/cp/hubs", Tier.OPERATOR, response = arr<com.tneff.cyppieagents.model.HubDescriptor>()),
        // --- ConnectorRoutes ---
        Op("POST", "/api/agents/{id}/connector", Tier.OPERATOR, request = json<ConnectorChoice>(), response = json<Agent>()),
        // --- ConfigRoutes (/api/config) — masked at rest, never re-rendered ---
        Op("GET", "/api/config/repo", Tier.PARTICIPANT, response = json<RepoConfigView>()),
        Op("GET", "/api/config/apikey", Tier.PARTICIPANT, response = json<ApiKeyView>()),
        Op("PUT", "/api/config/repo", Tier.OPERATOR, request = json<RepoConfigRequest>(), response = json<RepoConfigView>()),
        Op("GET", "/api/config/repo/reprovision-preview", Tier.OPERATOR, response = json<com.tneff.cyppieagents.model.ReprovisionPreview>()), // CYP-466: honest discard-confirm (live at-risk agents)
        Op("PUT", "/api/config/apikey", Tier.OPERATOR, request = json<ApiKeyRequest>(), response = json<ApiKeyView>()),

        // --- CompactRoutes (/api/compact) — CYP-326 compact orchestration config/status ---
        Op("GET", "/api/compact/status", Tier.PARTICIPANT, response = json<com.tneff.cyppieagents.model.CompactStatus>()),
        Op("POST", "/api/compact/config", Tier.OPERATOR, request = json<com.tneff.cyppieagents.model.CompactConfig>(), response = json<com.tneff.cyppieagents.model.CompactStatus>()),
        // --- ProjectRoutes (/api/projects) ---
        Op("GET", "/api/projects", Tier.OPERATOR, response = json<ProjectsView>()),
        Op("POST", "/api/projects", Tier.OPERATOR, request = json<CreateProjectRequest>(), response = json<Project>()),
        Op("POST", "/api/projects/switch", Tier.OPERATOR, request = json<SwitchActiveRequest>(), response = json<ProjectsView>()),
        Op("PUT", "/api/projects/{id}", Tier.OPERATOR, request = json<RenameProjectRequest>(), response = json<Project>()),
        // CYP-739: the DELETE genuinely returns a ProjectDeleteReceipt body (ProjectRoutes → deleter.delete()), NOT
        // 204/no-body. Declaring it here (in-place — @Serializable server type, walked by descriptor like
        // RevokedParticipantTokens; no :core promotion needed) makes openapi carry the schema so the web-ts consumer
        // generates the type instead of hand-modelling it (drift-proof; closes the latent 204-cast bug Dev5 pinned).
        Op("DELETE", "/api/projects/{id}", Tier.OPERATOR, response = json<com.tneff.cyppieagents.boot.ProjectDeleteReceipt>()),
        // --- ChannelShareRoutes (/api/channels/{id}/share) ---
        Op("GET", "/api/channels/{id}/share", Tier.PARTICIPANT, response = json<ChannelShareView>()),
        Op("PUT", "/api/channels/{id}/share", Tier.OPERATOR, response = json<ChannelShareView>()),
        Op("DELETE", "/api/channels/{id}/share", Tier.OPERATOR, response = json<ChannelShareView>()),
        // --- EventRoutes (CYP-186 BE2: event-log is secret-free metadata → MEMBER-readable; the ?projectId
        //     cross-project override stays operator-only, but the read tier itself is MEMBER, matching settings) ---
        Op("GET", "/api/events", Tier.MEMBER, response = json<EventPage>()),
        Op("GET", "/api/capacity", Tier.MEMBER, response = json<com.tneff.cyppieagents.model.Capacity>()), // CYP-417 (S-G)
        Op("GET", "/api/server-now", Tier.PARTICIPANT, response = json<com.tneff.cyppieagents.model.ServerNow>()), // CYP-421 (a)
        Op("POST", "/api/ws-ticket", Tier.PARTICIPANT, response = json<com.tneff.cyppieagents.model.WsTicket>()), // CYP-286: short-lived single-use WS ticket
        Op("GET", "/api/connectors", Tier.PARTICIPANT, response = json<com.tneff.cyppieagents.model.ConnectorsView>()), // CYP-462: connector catalog + declared fidelity (picker preview)
        // --- ReportRoutes (/api/reports) ---
        Op("GET", "/api/reports", Tier.OPERATOR, response = arr<ReportSnapshot>()),
        Op("GET", "/api/reports/{id}", Tier.OPERATOR, response = json<ReportSnapshot>()),
        Op("POST", "/api/reports", Tier.OPERATOR, request = json<GenerateReportRequest>(), response = json<ReportSnapshot>()),
        // --- ParticipantTokenRoutes (CYP-234b-3 #8: operator mint/revoke of the BYO-machine token class) ---
        Op("POST", "/api/participant-tokens", Tier.OPERATOR, request = json<MintParticipantTokenRequest>(), response = json<MintedParticipantToken>()),
        Op("GET", "/api/participant-tokens", Tier.OPERATOR, response = arr<ParticipantTokenSummary>()),
        Op("DELETE", "/api/participant-tokens", Tier.OPERATOR, response = json<RevokedParticipantTokens>()),
        // --- WorkspaceRoutes ---
        Op("GET", "/api/workspace/members", Tier.OPERATOR, response = arr<WorkspaceMember>()),
        Op("GET", "/api/audit", Tier.OPERATOR, response = arr<OperatorAudit>()),
        // --- AuthMeRoutes (public, content-free whoami) ---
        Op("GET", "/api/auth/me", Tier.PUBLIC, response = json<AuthMe>()),
        // --- RegisterRoutes (public, branch-invariant) ---
        Op("POST", "/api/auth/register", Tier.PUBLIC, request = json<RegisterRequest>(), response = Body.None),
        // --- SettingsRoutes (/api/auth/settings, MEMBER self-management) ---
        Op("POST", "/api/auth/settings/password", Tier.MEMBER, request = json<ChangePasswordRequest>(), response = Body.None),
        Op("POST", "/api/auth/settings/email", Tier.MEMBER, request = json<ChangeEmailRequest>(), response = Body.None),
    )
}
