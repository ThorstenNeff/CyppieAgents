package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.avatar.AvatarBlobStore
import com.tneff.cyppieagents.avatar.AvatarImageProcessor
import com.tneff.cyppieagents.avatar.AvatarPresetResolver
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentMgmtGuard
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException

/**
 * Runtime agent CRUD (S14 / CYP-97 — AGENT-MANAGEMENT §2). Orchestrates the touchy moving parts behind
 * the operator-gated endpoints: the [AgentMgmtGuard] invariants, the [HubState] topology (spoke + ACL),
 * the [AgentConfigRegistry] (persona/launch the connector spawns with), the [LifecycleManager] (known
 * set + stop), and the worktree side-effects — each delegated, never re-implemented.
 *
 * Disclosure model the doc requires: **add does NOT spawn** (the agent is STOPPED; start is the CYP-73
 * lifecycle); **edit takes effect on the next spawn** (the connector reads [AgentConfigRegistry] at
 * `open()`); **remove stops the session** and, only on the warned path, deletes the worktree (the agent
 * branch is never auto-deleted). All guards are fail-closed: a rejection mutates nothing.
 */
class AgentManagement(
    private val state: HubState,
    private val lifecycle: LifecycleManager,
    private val configs: AgentConfigRegistry,
    private val ensureWorktree: (worktreeName: String) -> Unit,
    private val deleteWorktree: (worktreeName: String) -> Unit,
    /**
     * CYP-122: invoked when an agent is created with a non-default connector (an opt-in to Connector B).
     * Routes through [ConnectorOptIn] (config + caps re-declare + `connector.optin` audit) so create-as-B
     * is audited identically to the dedicated opt-in change. Default no-op (tests / no-event installs).
     */
    private val onConnectorOptIn: (agentId: String, kind: ConnectorKind) -> Unit = { _, _ -> },
    /**
     * CYP-171 / E2.6 (S3) — mints/revokes the per-agent bearer token for a **remote** create/remove.
     * Null (tests / non-remote installs) → no token issuance. The minted token is disclosed ONCE via the
     * [CreatedAgent] return of [add]; identity stays `token→agentId` (no client-supplied agentId).
     */
    private val remoteToken: RemoteTokenIssuer? = null,
    /**
     * CYP-210 — the durable overlay for per-agent name/color/persona/launch. Non-null persists an edit so it
     * survives a restart (overlaid over the `platform.config.json` seed at boot). Null (tests) = no durability.
     */
    private val overrides: AgentOverrideStore? = null,
    /**
     * CYP-210/CYP-246 — resolves the ACTIVE project the overrides/avatar blobs are scoped to, so a CRUD op
     * writes into whichever project is active at the time (not a boot-frozen constant). Defaults to the
     * single MVP project. Boot wires it to `{ state.activeProjectId }` so it follows a switch (CYP-246);
     * keeping it a resolver (not the live field) leaves it trivially injectable in tests.
     */
    private val activeProjectId: () -> String = { com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID },
    /** CYP-215 — the on-disk store for re-encoded avatar PNGs. Null (tests) = no blob persistence. */
    private val avatarBlobs: AvatarBlobStore? = null,
    /** CYP-215 — resolves a Preset `(style,seed)` to bundled PNG bytes on serve. Null = no preset bytes. */
    private val avatarPresets: AvatarPresetResolver? = null,
) {
    private val lock = Any()

    /** Lightweight list (GET /api/agents) with each agent's live run-state. */
    fun list(): List<Agent> = state.agents.map { it.copy(runState = lifecycle.runStateOf(it.id) ?: it.runState) }

    /** Edit-prefill detail (CYP-101 / GET /api/agents/{id}) — the real launch + persona. */
    fun detail(id: String): AgentDetail {
        val a = state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        val cfg = configs.configOf(id)
        return AgentDetail(a.id, a.name, a.role, a.worktree, cfg?.launch ?: "claude", cfg?.persona, color = a.color, avatar = a.avatar)
    }

    /** Register a new agent (NOT spawned). Throws the §2 4xx on a guard violation. Returns the agent and,
     *  for a remote create, the **once-disclosed** minted token (CYP-171). */
    fun add(spec: NewAgentSpec): CreatedAgent = synchronized(lock) {
        AgentMgmtGuard.validateAdd(state.agents, spec)?.let { throw codeToException(it) }
        val worktree = spec.worktree?.ifBlank { null }?.trim() ?: spec.id.trim()
        // CYP-215: an optional preset avatar at create (Preset-only by type). Validate the style FAIL-CLOSED
        // against the self-hosted allow-list; a blank seed defaults to the agent id (deterministic).
        val avatar = spec.avatar?.let { normalizePreset(it, spec.id.trim()) }
        val agent = Agent(spec.id.trim(), spec.name.trim(), spec.role, worktree, AgentRunState.STOPPED, connectorKind = spec.connectorKind, color = spec.color?.ifBlank { null }, avatar = avatar)
        // CYP-259 (c) — check-before-mutate: run the ONE fallible external op (worktree creation; git can
        // fail on disk/permissions) BEFORE any in-memory topology/config mutation, so a failure leaves NO
        // partial state / orphan agent (a bare worktree dir is idempotent + harmless, reused on retry). The
        // validate + preset checks above already threw before here; the mutations below are all in-memory.
        ensureWorktree(worktree)              // create the worktree; CLAUDE.md is written at first spawn
        configs.put(agent.id, spec.launch?.ifBlank { null }?.trim() ?: "claude", spec.persona?.ifBlank { null })
        state.addAgent(agent)                 // spoke channel + ACL, projectId-stamped (fail-closed)
        if (avatar != null) overrides?.setAvatar(activeProjectId(), agent.id, avatar) // durable overlay (restart-survive)
        lifecycle.register(agent.id, worktree) // known + STOPPED — start is the CYP-73 lifecycle
        // CYP-122: a non-default connector at create is an opt-in → audited + caps re-declared (server-enforced).
        if (spec.connectorKind != ConnectorKind.STREAM_JSON) onConnectorOptIn(agent.id, spec.connectorKind)
        // CYP-171: a remote/BYOA agent gets a server-minted per-agent token, disclosed ONCE in this response
        // (SEC-OP1's reserved-id guard in validateAdd already rejected an id colliding with the operator).
        val token = if (spec.remote) remoteToken?.issue(agent.id) else null
        CreatedAgent(agent, token)
    }

    /** Write agent config (effective next spawn). Omitted/blank persona/launch PRESERVE the stored value. */
    fun edit(id: String, edit: AgentEdit): Agent = synchronized(lock) {
        AgentMgmtGuard.validateEdit(state.agents, id, edit)?.let { throw codeToException(it) }
        val cur = configs.configOf(id) ?: AgentRuntimeConfig("claude", null)
        val persona = edit.persona?.ifBlank { null } ?: cur.persona // PRESERVE (no blank→null clear)
        val launch = edit.launch?.ifBlank { null } ?: cur.launch
        configs.put(id, launch, persona)
        // CYP-210: display name/color (blank/omitted → PRESERVE; null = no change). id stays IMMUTABLE — it is
        // not a field on AgentEdit, so it can never be touched here. Pure display fields → no ACL/topology.
        val newName = edit.name?.ifBlank { null }
        val newColor = edit.color?.ifBlank { null }
        state.editAgent(id, newName, newColor)
        // CYP-210: persist the override so name/color/persona/launch SURVIVE a restart (overlaid over the
        // config seed at boot). The store applies the same blank→preserve rule against its own stored value.
        overrides?.put(activeProjectId(), id, name = edit.name, color = edit.color, persona = edit.persona, launch = edit.launch)
        // CYP-215: a non-null avatar sets/replaces it with a PRESET (Preset-only by type → an Upload ref can't be
        // forged on this JSON path). Validate the style FAIL-CLOSED; blank seed → the agent id. null = PRESERVE
        // (a name-only edit never wipes the avatar; the clear path is DELETE .../avatar). Switching to a preset
        // orphans any prior upload blob → delete it (no dangling bytes).
        edit.avatar?.let { preset ->
            val normalized = normalizePreset(preset, id)
            state.setAvatar(id, normalized)
            overrides?.setAvatar(activeProjectId(), id, normalized)
            avatarBlobs?.delete(activeProjectId(), id)
        }
        // persona/launch take effect on the next spawn (connector reads configs at open()); name/color are live.
        state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
    }

    /**
     * Stop + remove the agent; [fate] decides the worktree. Validated BEFORE any destructive step
     * (fail-closed: never stop/delete then reject). Suspends to await the process's death (no zombie).
     */
    suspend fun remove(id: String, fate: WorktreeFate) {
        AgentMgmtGuard.validateRemove(state.agents, id)?.let { throw codeToException(it) }
        val worktree = state.agent(id)?.worktree
            ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        lifecycle.stop(id)        // CYP-73 stop: removeAndAwait — process gone before we drop the agent
        state.removeAgent(id)     // clean topology removal — spoke channel + every ACL entry gone
        configs.remove(id)
        lifecycle.forget(id)
        remoteToken?.revoke(id)   // CYP-171 / SEC5: revoke any minted token (idempotent) — agentFor → null
        overrides?.removeAgent(activeProjectId(), id)   // CYP-210/215: drop the durable overlay (name/color/avatar)
        avatarBlobs?.delete(activeProjectId(), id)      // CYP-215: purge the stored avatar blob (no dangling bytes)
        if (fate == WorktreeFate.DELETE) deleteWorktree(worktree) // branch agent/<id> NOT touched (§9.3)
    }

    /**
     * CYP-215 — the **security-critical** custom-avatar upload. [bytes] are UNTRUSTED: [AvatarImageProcessor]
     * validates fail-closed (size → magic-bytes → dimension cap → bounded decode → center-crop → re-encode
     * PNG strip) and throws [com.tneff.cyppieagents.avatar.AvatarRejected] on any failure (the route maps that
     * to a uniform 400 while logging the fired check). On success the RE-ENCODED bytes are stored (never the
     * original), and the avatar becomes an [AgentAvatar.Upload] carrying the server-minted content-hash `ref`.
     */
    fun uploadAvatar(id: String, bytes: ByteArray): AvatarUploadReceipt = synchronized(lock) {
        state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        val result = AvatarImageProcessor.process(bytes) // throws AvatarRejected → uniform 400 at the route
        avatarBlobs?.write(activeProjectId(), id, result.png)
        val avatar = AgentAvatar.Upload(result.ref)
        state.setAvatar(id, avatar)
        overrides?.setAvatar(activeProjectId(), id, avatar)
        AvatarUploadReceipt(id, result.ref, result.bytesIn, result.bytesOut, result.srcFormat, result.srcWidth, result.srcHeight, result.outWidth, result.outHeight)
    }

    /** CYP-215 — clear the avatar back to the default (null): drop the metadata (state + overlay) and the blob. */
    fun clearAvatar(id: String) = synchronized(lock) {
        state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        state.setAvatar(id, null)
        overrides?.setAvatar(activeProjectId(), id, null)
        avatarBlobs?.delete(activeProjectId(), id)
    }

    /**
     * CYP-215 — resolve the agent's current avatar to servable PNG bytes: an [AgentAvatar.Upload] → the stored
     * re-encoded blob; a [AgentAvatar.Preset] → the self-hosted DiceBear bytes (egress-free). null (no avatar /
     * unknown agent / blob or preset-set missing) → the route 404s and the client falls back to its default.
     */
    fun serveAvatar(id: String): ServedAvatar? {
        val a = state.agent(id) ?: return null
        return when (val av = a.avatar) {
            is AgentAvatar.Upload -> avatarBlobs?.read(activeProjectId(), id)?.let { ServedAvatar(it, av.ref) }
            is AgentAvatar.Preset -> avatarPresets?.resolve(av.style, av.seed)?.let { ServedAvatar(it, null) }
            null -> null
        }
    }

    /**
     * CYP-219 — resolve a DiceBear PRESET PREVIEW `(style, seed)` to servable PNG bytes, independent of any
     * agent's stored avatar (the picker grid probes arbitrary combinations before committing). Egress-free:
     * bytes come from the self-hosted bundled set via [AvatarPresetResolver] — which validates `style` against
     * the allow-list (unknown/traversal-y → null) and hashes `seed` to an index (never a path). null (unknown
     * style / no bundled asset) → the route 404s and the grid degrades to a placeholder. The [ServedAvatar.ref]
     * is a content hash → a deterministic ETag for the many preview requests.
     */
    fun previewAvatar(style: String, seed: String): ServedAvatar? {
        val png = avatarPresets?.resolve(style, seed.ifBlank { "default" }) ?: return null
        return ServedAvatar(png, com.tneff.cyppieagents.avatar.AvatarImageProcessor.contentRef(png))
    }

    /** Validate a preset's style FAIL-CLOSED against the self-hosted allow-list; default a blank seed to [id]. */
    private fun normalizePreset(preset: AgentAvatar.Preset, id: String): AgentAvatar.Preset {
        if (preset.style !in AvatarPresetResolver.ALLOWED_STYLES) {
            throw BadRequestException("avatar preset style '${preset.style}' is not allowed", code = "avatar_style_not_allowed")
        }
        return AgentAvatar.Preset(preset.style, preset.seed.ifBlank { id })
    }

    private fun codeToException(code: String): Exception = when (code) {
        "invalid_agent" -> BadRequestException("invalid agent spec", code = "invalid_agent")
        "agent_exists" -> ConflictException("agent already exists", code = "agent_exists")
        "po_already_exists" -> ConflictException("a PO already exists", code = "po_already_exists")
        "last_po" -> ConflictException("cannot remove or demote the only PO", code = "last_po")
        "agent_not_found" -> NotFoundException("agent not found", code = "agent_not_found")
        else -> BadRequestException(code, code = code)
    }
}

/**
 * CYP-215 — the success diagnostic of an avatar upload (server-side, NOT on the wire): feeds the log line
 * the directive asks for ("bytes-in/out + dims"). The route logs it; the client gets the updated agent.
 */
data class AvatarUploadReceipt(
    val agentId: String,
    val ref: String,
    val bytesIn: Int,
    val bytesOut: Int,
    val srcFormat: String,
    val srcWidth: Int,
    val srcHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
)

/** CYP-215 — resolved avatar bytes to serve (server-side). [ref] (if present) → the cache-busting ETag. */
class ServedAvatar(val png: ByteArray, val ref: String?)
