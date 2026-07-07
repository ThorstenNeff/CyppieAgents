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

/** New-agent fields (CYP-86 / POST /api/agents). CYP-310: `persona` is DEPRECATED + IGNORED — a new agent
 *  gets an EMPTY CLAUDE.md; manage it via `POST /api/agents/{id}/claude-md`. Kept for wire-compat (removal =
 *  CYP-311). */
@Serializable
data class NewAgentSpec(
    val id: String,
    val name: String,
    val role: Role,
    /** CYP-310: DEPRECATED + IGNORED (no CLAUDE.md write on add) — use the claude-md endpoints. Removal = CYP-311. */
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
    /**
     * CYP-215 — optional avatar at create. **Preset-only on the JSON write path** ([AgentAvatar.Preset]):
     * a client cannot express an [AgentAvatar.Upload] here (server-authoritative — an upload `ref` is only
     * ever minted by the validated multipart endpoint). null = no override.
     */
    val avatar: AgentAvatar.Preset? = null,
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
 * and intentionally absent (id is IMMUTABLE).** **Omitted/blank `role`/`name`/`persona`/`launch`/`color`
 * PRESERVE the stored value** (no blank→null clear — PO-confirmed, data-safety footgun closed); deliberate
 * clearing is a separate future ticket. CYP-210 added editable display `name` (was deliberately omitted) +
 * `color`. CYP-215 added [avatar] (Preset-only; upload + clear are their own endpoints — see below).
 */
@Serializable
data class AgentEdit(
    /**
     * CYP-313 — the target role, now **nullable/omittable** (`null` = PRESERVE, consistent with
     * `name`/`color`/`launch`). Only a NON-NULL role is an EXPLICIT role change that the guard checks
     * against the PO topology (`po_already_exists`/`last_po`); a display-only edit (e.g. a colour change)
     * omits `role` and can therefore never be mis-read as a demotion of the only PO (the CYP-313 bug). Role
     * is otherwise guard-only — the edit apply path never rewrites the stored role.
     */
    val role: Role? = null,
    /** CYP-310: DEPRECATED + IGNORED (no longer drives CLAUDE.md) — use the claude-md endpoints. Removal = CYP-311. */
    val persona: String? = null,
    val launch: String? = null,
    /** CYP-210 — editable display name (blank/omitted → PRESERVE). The agent `id` stays immutable. */
    val name: String? = null,
    /** CYP-210 — editable display colour (blank/omitted → PRESERVE; see [Agent.color]). */
    val color: String? = null,
    /**
     * CYP-215 — set/replace the avatar with a self-hosted DiceBear **preset**. **Preset-only by
     * construction**: an [AgentAvatar.Upload] cannot be expressed here, so this JSON path can never forge
     * an upload `ref` (server-authoritative — uploads go through the validated multipart endpoint).
     * `null`/omitted = PRESERVE the stored avatar (consistent with the other edit fields — a name-only edit
     * never wipes the avatar). To go BACK to the default (no avatar) use `DELETE /api/agents/{id}/avatar`;
     * to set an uploaded image use `POST /api/agents/{id}/avatar` (multipart). Both replace whatever was
     * stored (an orphaned upload blob is cleaned up server-side).
     */
    val avatar: AgentAvatar.Preset? = null,
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
    /**
     * CYP-310: DEPRECATED — no longer the CLAUDE.md source. The stored (now vestigial) persona field; the live
     * CLAUDE.md is read via `GET /api/agents/{id}/claude-md`. Kept for wire-compat; removal = CYP-311.
     */
    @Deprecated("CYP-310: not the CLAUDE.md source; read GET /api/agents/{id}/claude-md. Removal = CYP-311.")
    val persona: String? = null,
    /** CYP-210 — the stored display colour, so the edit dialog prefills it (see [Agent.color]). */
    val color: String? = null,
    /**
     * CYP-215 — the stored avatar (full [AgentAvatar]: Preset or the server-minted Upload `ref`), so the
     * settings dialog prefills the current selection + resolves the preview. `null` = no override.
     */
    val avatar: AgentAvatar? = null,
)

/**
 * CYP-310 — the live worktree `CLAUDE.md` of an agent (read: `GET /api/agents/{id}/claude-md`; the echo of a
 * write: `POST`). [content] is the CURRENT file text ("" when absent); [exists] flags file presence; [version]
 * is the content hash (sha-256 hex of the file bytes, `null` when absent) for optimistic concurrency — a `POST`
 * must echo it back as [ClaudeMdUpdate.expectedVersion]. Read live each call, so it reflects external edits +
 * the agent's own edits (there is no server-cached copy). NOT the stored `persona` — that field is deprecated.
 */
@Serializable
data class ClaudeMdView(val agentId: String, val content: String, val exists: Boolean, val version: String? = null)

/**
 * CYP-310 — body of `POST /api/agents/{id}/claude-md`: a HARD overwrite (no merge) of the worktree `CLAUDE.md`.
 * [expectedVersion] is optimistic concurrency: the [ClaudeMdView.version] the client last read. The server
 * re-hashes the CURRENT file and, if it differs, rejects with 409 `claude_md_stale` (an unseen external/agent
 * edit would be clobbered) — WITHOUT writing. `null` = the client expects no file yet (first create). A write
 * is EFFECT_DEFERRED: the file changes immediately, but a running session already read its CLAUDE.md, so it
 * takes effect on the next spawn/restart.
 */
@Serializable
data class ClaudeMdUpdate(val content: String, val expectedVersion: String? = null)
