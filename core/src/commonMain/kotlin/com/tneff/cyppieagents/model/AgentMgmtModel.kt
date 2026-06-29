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
)

/** Operator opt-in to a connector (CYP-122 / Doc 10 §3,§5). Body of the dedicated, audited set-connector
 *  action — separate from [AgentEdit] so a connector change is always an explicit, logged decision. */
@Serializable
data class ConnectorChoice(val connectorKind: ConnectorKind)

/**
 * Editable agent config (CYP-88 / PUT /api/agents/{id}). id and worktree are identity/path-defining and
 * intentionally absent. **Omitted/blank `persona`/`launch` PRESERVE the stored value** (no blank→null
 * clear — PO-confirmed, data-safety footgun closed); deliberate clearing is a separate future ticket.
 */
@Serializable
data class AgentEdit(
    val role: Role,
    val persona: String? = null,
    val launch: String? = null,
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
)
