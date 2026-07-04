package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * Agent-management wire contract (S14 / CYP-97 — AGENT-MANAGEMENT §2). The DTOs live in `:core` so the
 * server endpoints and the client `AgentManagementRepository` compile against ONE definition (the doc's
 * "Vertrags-DTOs gehören in :core"). The list view is the lightweight [Agent]; [AgentDetail] is the
 * edit-prefill view that additionally carries `launch` + `persona` (the CLAUDE.md text) — kept out of
 * the list because a persona can be large (CYP-101 seam).
 *
 * **The UI calls; the connector owns spawn/worktree/CLAUDE.md.** Add does NOT spawn (start is the CYP-73
 * lifecycle); edit takes effect on the next spawn; remove stops the session and, on the warned path,
 * deletes the worktree (the agent branch is never auto-deleted).
 */

/** New-agent fields (CYP-86 / POST /api/agents). `persona` is written to the worktree's CLAUDE.md. */
@Serializable
data class NewAgentSpec(
    val id: String,
    val name: String,
    val role: Role,
    val persona: String? = null,
    val launch: String? = null,
    val worktree: String? = null,
    /**
     * Connector choice (Doc 10 §1 / CYP-122). Default `STREAM_JSON` (Connector A). Creating an agent with
     * `MCP` (Connector B) is an opt-in to lower fidelity — server-enforced + audited (`connector.optin`).
     * Deliberately ABSENT from [AgentEdit]: an existing agent's connector is changed only through the
     * dedicated operator-gated opt-in action, never silently via a general edit (no "off-message" path).
     */
    val connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /**
     * CYP-171 / E2.6 (S3) — create this agent as a **remote/BYOA** agent (the runtime twin of
     * [com.tneff.cyppieagents.boot.AgentConfig]`.remote`): not spawned locally; the server **mints a
     * per-agent bearer token** (returned ONCE in [CreatedAgent]) and the agent joins over the wire
     * (`/ws/hub`). Default false (a normal local agent). The minted token is the credential; identity
     * stays `token→agentId` — there is NO client-supplied agentId/role anywhere.
     */
    val remote: Boolean = false,
    /** CYP-210 — optional per-agent display colour (nullable hex string seam; see [Agent.color]). null =
     *  no override (the client derives a default). Final hex-vs-slot shape settled with CYP-209. */
    val color: String? = null,
)

/**
 * CYP-171 — the 201 response of `POST /api/agents`. [token] is non-null **only** for a remote create and
 * is the **one and only** time the server discloses the minted bearer token (the operator hands it to the
 * BYOA user out-of-band). It is NEVER re-rendered: `GET /api/agents` ([Agent]) and the detail view
 * ([AgentDetail]) carry no token. A normal (local) create returns [token] = null.
 */
@Serializable
data class CreatedAgent(val agent: Agent, val token: String? = null)

/** Operator opt-in to a connector (CYP-122 / Doc 10 §3,§5). Body of the dedicated, audited set-connector
 *  action — separate from [AgentEdit] so a connector change is always an explicit, logged decision. */
@Serializable
data class ConnectorChoice(val connectorKind: ConnectorKind)

/**
 * Editable agent config (CYP-88 / PUT /api/agents/{id}). **`id` and `worktree` are identity/path-defining
 * and intentionally absent (id is IMMUTABLE).** **Omitted/blank `name`/`persona`/`launch`/`color` PRESERVE
 * the stored value** (no blank→null clear — PO-confirmed, data-safety footgun closed); deliberate clearing
 * is a separate future ticket. CYP-210 added editable display `name` (was deliberately omitted) + `color`.
 */
@Serializable
data class AgentEdit(
    val role: Role,
    val persona: String? = null,
    val launch: String? = null,
    /** CYP-210 — editable display name (blank/omitted → PRESERVE). The agent `id` stays immutable. */
    val name: String? = null,
    /** CYP-210 — editable display colour (blank/omitted → PRESERVE; see [Agent.color]). */
    val color: String? = null,
)

/** Worktree fate on removal (CYP-87). [KEEP] is the safe default; [DELETE] is the warned, destructive path. */
@Serializable
enum class WorktreeFate { KEEP, DELETE }

/**
 * Edit-prefill detail view (CYP-101 / GET /api/agents/{id}). Carries the real `launch` + `persona` so the
 * edit dialog prefills the actual values — the list [Agent] stays lightweight.
 */
@Serializable
data class AgentDetail(
    val id: String,
    val name: String,
    val role: Role,
    val worktree: String,
    val launch: String,
    val persona: String? = null,
    /** CYP-210 — the stored display colour, so the edit dialog prefills it (see [Agent.color]). */
    val color: String? = null,
)
