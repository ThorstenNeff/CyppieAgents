package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclGuard
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.ProjectScope
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException

/**
 * Mutable hub configuration (agents, channels, ACL entries) and the derived [AclMatrix].
 * The matrix is rebuilt on every ACL change so enforcement always reads a consistent snapshot;
 * the *decision* itself lives only in [AclMatrix] (single source).
 */
class HubState(
    initialAgents: List<Agent>,
    initialChannels: List<Channel>,
    initialEntries: List<AclEntry>,
    /** Seed for [activeProjectId] (the body property below); single-sourced from `platform.config.json`. */
    activeProjectId: String = DEFAULT_PROJECT_ID,
    /**
     * The privileged operator participant id (CYP-18), if the topology has one. Stored so a runtime
     * [addAgent] (CYP-97) puts the operator on the new spoke too — same membership as the boot spokes.
     */
    private val operatorId: String? = null,
    /**
     * Resolver for the cross-project shares reaching INTO a given active project (S17 / CYP-93): the
     * set of channel ids the [AclMatrix] permit lets span the boundary. Reads the [ChannelShareStore]
     * in production; defaulted to "no shares" (pure S13 fail-closed) for tests / the dev install. The
     * result is recomputed on [rescope] (the active project changed) and on [refreshShares] (a share
     * was set/revoked), then folded into every matrix this state builds — the single chokepoint.
     */
    private val sharedInboundProvider: (String) -> Set<String> = { emptySet() },
) {
    private val lock = Any()

    /**
     * The single active tenant (S12 / CYP-81), single-sourced from `platform.config.json` (`projectId`,
     * default [DEFAULT_PROJECT_ID]). Threaded into every [AclMatrix] this state builds so project
     * scoping is decided in one place, and used by the [Hub] to stamp posted messages.
     *
     * **Mutable at runtime via [rescope] (S13 / CYP-102):** a project switch flips this pointer and
     * rebuilds the matrix, so the comm read-paths (channels/inbox/acl/ws-comm) re-scope to the new
     * active project WITHOUT a restart. MVP = 1 project, so it stays [DEFAULT_PROJECT_ID] until a switch.
     */
    @Volatile
    var activeProjectId: String = activeProjectId
        private set

    /**
     * Channel ids authorized to reach INTO the active project (S17 / CYP-93). Recomputed from
     * [sharedInboundProvider] on construction, on [rescope], and on [refreshShares]; passed to every
     * [AclMatrix] so the cross-project permit is decided at the one chokepoint.
     */
    @Volatile
    var sharedInboundChannelIds: Set<String> = sharedInboundProvider(activeProjectId)
        private set

    /** The hub's agents. Mutable at runtime via [addAgent]/[removeAgent] (CYP-97). */
    @Volatile
    var agents: List<Agent> = initialAgents
        private set

    @Volatile
    var channels: List<Channel> = initialChannels
        private set

    @Volatile
    var entries: List<AclEntry> = initialEntries
        private set

    @Volatile
    var acl: AclMatrix = AclMatrix(initialChannels, initialEntries, activeProjectId, sharedInboundChannelIds)
        private set

    /**
     * CYP-246 — the INACTIVE projects' agent lists, parked on [rescope]. The ACTIVE project's agents are the
     * live [agents] field above; on a switch the active list is stashed here under its projectId and the
     * target project's list is restored (or an EMPTY list minted for a fresh project that has none yet). This
     * partitions the AGENT SET per project WITHOUT a second HubState — the one piece the comm chokepoint could
     * NOT isolate, because [Agent] carries no projectId and `GET /api/agents` reads [agents] directly (the leak
     * that survived a browser reload). [channels]/[entries] need NO such stash: they ARE project-stamped and the
     * [AclMatrix] already filters them by the active project (CYP-81/102) — only [agents] was un-scoped. It does
     * NOT touch the per-agent lifecycle/spawn/worktree (still boot-pinned) — that is the deferred S17. Guarded
     * by [lock] like every other mutation.
     */
    private val stashedAgents = HashMap<String, List<Agent>>()

    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id }

    /**
     * Upsert one ACL entry (operator action) and rebuild the matrix.
     *
     * PO-lockout guardrail (CYP-49): the guard runs **atomically under the lock, before persisting**,
     * over the *recomputed* candidate [AclMatrix] — never the request payload — so it catches every
     * lockout vector (canWrite, canRead, removed membership) in one fail-closed answer. The PO is the
     * hub of hub-and-spoke; if a change would strip its read or write on any spoke it hubs, we reject
     * with 409 and commit nothing. Legitimate worker toggles are untouched. The candidate matrix is
     * reused for the commit, so the checked state and the persisted state cannot drift.
     *
     * ★ CYP-663 — ACCEPTED EDGE (Auftraggeber decision, 2026-07-16; do NOT "fix" this unprompted). This guard covers
     * **`po.id` ONLY**. The **operator** ACL column ([HubState.OPERATOR_ID]) is togglable via `PUT /api/acl` WITHOUT a
     * guardrail and IS reachable from the UI — real, not cosmetic. It is accepted because **recovery is structurally
     * guaranteed**: `PUT /api/acl` is gated on `AuthRole.OPERATOR` (`CommRoutes.kt:225`), and that role is **runtime-
     * IMMUTABLE** (`RoleStore` has no setter; there is no role-mutation endpoint). So the comm-ACL — which is ALL this
     * guard concerns — is NOT the operator's admin seam, and no comm-ACL toggle can permanently brick the operator
     * (a self-toggled operator column is a recoverable comm-visibility degradation, re-granted via `PUT /api/acl`).
     * Two-net verified (this team's measurement + Team-2's Backend2, independent; Team-2 live probe: 200 observed / 409
     * control). Long-form: **CYP-663**. No re-litigate without the Auftraggeber.
     *
     * ★ NOTE (the confusion that triggered the two-net read): `PlatformWiring.kt:448` (CYP-186 operator-**TOKEN**
     * kill-switch — "effective only once a role-OPERATOR exists") is a **DIFFERENT seam**, **NOT** this guard. The two
     * comments both read like "a guard"; they are not the same one. This note nails that down so the detour is paid once.
     */
    fun setAcl(entry: AclEntry): AclEntry = synchronized(lock) {
        // CYP-188: single-source the tenant scope — a `PUT /api/acl` grant is ALWAYS for the active project, so
        // stamp it server-side and IGNORE the client's `AclEntry.projectId` (defaults to DEFAULT_PROJECT_ID). Without
        // this, a grant in a non-default active project is DEFAULT-stamped and the [AclMatrix] `ProjectScope.permits`
        // (exact-match) filters it OUT → canRead/canWrite false → 403 even though membership syncs below. Also
        // prevents cross-project entry injection via this path (cross-project sharing is the ChannelShare permit).
        val entry = entry.copy(projectId = activeProjectId)
        // CYP-188: fail-fast — a grant for a channelId with NO `Channel` object is rejected (404) instead of
        // persisting a silent ORPHAN entry that `GET /api/acl` shows yet every later send/read 403s (there is no
        // `Channel.members` to sync the grantee into → isMember=false). This is exactly the misleading live symptom
        // (a granted human on a non-existent channel). `channels` holds the active project's real channels.
        if (channels.none { it.id == entry.channelId }) {
            throw NotFoundException("channel '${entry.channelId}' not found", code = "channel_not_found")
        }
        val next = entries.filterNot { it.channelId == entry.channelId && it.agentId == entry.agentId } + entry
        // S17 / CYP-112: membership IS the per-agent ACL (CYP-93). An entry that grants access
        // (canRead || canWrite) makes the agent a member of that channel; a revoke to no access removes
        // it. Synced into the channel's members BEFORE the candidate matrix is built, so the PO-lockout
        // guard and every read see correct membership — and so a cross-project grantee provisioned via
        // `PUT /api/acl` actually becomes a member of the shared channel (unblocks J3 grantee-READ: the
        // share permit puts the channel in scope, this makes the grantee a member of it).
        val grantsAccess = entry.canRead || entry.canWrite
        val nextChannels = channels.map { ch ->
            if (ch.id != entry.channelId) {
                ch
            } else {
                val member = entry.agentId in ch.members
                when {
                    grantsAccess && !member -> ch.copy(members = ch.members + entry.agentId)
                    !grantsAccess && member -> ch.copy(members = ch.members - entry.agentId)
                    else -> ch
                }
            }
        }
        val candidate = AclMatrix(nextChannels, next, activeProjectId, sharedInboundChannelIds)
        val po = agents.firstOrNull { it.role == Role.PO }
        if (po != null) {
            val lockedOut = AclGuard.lockedOutPoHubChannel(candidate, po.id, poHubChannelIds())
            if (lockedOut != null) {
                throw ConflictException(
                    "refusing ACL change: the PO must keep read and write on hub channel '$lockedOut' " +
                        "— removing it would break hub-and-spoke coordination",
                    // Stable, machine-readable code (contract with UIUX/Dev): the UI maps this to its
                    // `acl_po_protected` state and falls back to the hub's truth (CYP-48/CYP-49).
                    code = "po_lockout_protected",
                )
            }
        }
        entries = next
        channels = nextChannels
        acl = candidate
        entry
    }

    /**
     * CYP-869 (OS-B) — create an arbitrary DIRECT/GROUP orchestration channel with per-member ACL seeded. The
     * single channel-creation mutation point; mirrors [setAcl] (synchronized, rebuild the [AclMatrix] from the
     * next channels+entries, assign atomically). **Fail-closed:** a [ChannelKind.HUB] kind is rejected (spokes stay
     * managed by boot/[addAgent], never this path); a blank id/name and a duplicate id are rejected BEFORE any
     * mutation. **Membership IS the ACL** — only members granted `canWrite` can send at the [com.tneff.cyppieagents.comm.Hub.postAsAgent]
     * chokepoint (unwritable-by-default); an agent granted neither read nor write gets no [AclEntry] and no
     * membership. Server-stamps the active [activeProjectId] (never a client projectId). Adds NO write path.
     */
    fun createChannel(id: String, name: String, kind: ChannelKind, members: List<ChannelMemberGrant>): Channel = synchronized(lock) {
        if (id.isBlank()) throw BadRequestException("channel id required", code = "channel_id_required")
        if (name.isBlank()) throw BadRequestException("channel name required", code = "channel_name_required")
        if (kind == ChannelKind.HUB) {
            throw BadRequestException(
                "cannot create a HUB channel via this API — hub-and-spoke is boot/agent-managed",
                code = "channel_kind_forbidden",
            )
        }
        // CYP-878 — fail-closed: the `po-*` worker-spoke prefix and `op-po` ([OP_PO_CHANNEL_ID]) are the RESERVED
        // hub-and-spoke namespace, minted ONLY by boot/[addAgent]. Creating an arbitrary channel with such an id
        // would collide with a future spoke — [spokeChannelFor]'s `firstOrNull` then resolves to whichever landed
        // first = a SILENT topology/ACL collision (operator creates `po-X` before worker X is added). Reject it.
        if (id == OP_PO_CHANNEL_ID || id.startsWith("po-")) {
            throw BadRequestException(
                "channel id '$id' is reserved for the hub-and-spoke topology (boot/agent-managed)",
                code = "channel_id_reserved",
            )
        }
        if (channels.any { it.id == id }) throw ConflictException("channel '$id' already exists", code = "channel_exists")
        // Membership IS the ACL (S17/CYP-93): a member granted access (canRead||canWrite) joins; neither = no-op.
        val granted = members.filter { it.canRead || it.canWrite }.distinctBy { it.agentId }
        val channel = Channel(id, name, kind, granted.map { it.agentId }, activeProjectId)
        val newEntries = granted.map { AclEntry(id, it.agentId, it.canRead, it.canWrite, activeProjectId) }
        val nextChannels = channels + channel
        val next = entries + newEntries
        val candidate = AclMatrix(nextChannels, next, activeProjectId, sharedInboundChannelIds)
        channels = nextChannels
        entries = next
        acl = candidate
        channel
    }

    /**
     * CYP-869 (OS-B) — rename a channel's DISPLAY name (id immutable). No ACL/topology impact (name is not in
     * [Channel.members] or [AclEntry]); rebuilds the matrix for consistency and returns the refreshed channel.
     * 404 if unknown; 400 on a blank name.
     */
    fun renameChannel(id: String, newName: String): Channel = synchronized(lock) {
        if (newName.isBlank()) throw BadRequestException("channel name required", code = "channel_name_required")
        if (channels.none { it.id == id }) throw NotFoundException("channel '$id' not found", code = "channel_not_found")
        val nextChannels = channels.map { if (it.id == id) it.copy(name = newName) else it }
        channels = nextChannels
        acl = AclMatrix(nextChannels, entries, activeProjectId, sharedInboundChannelIds)
        nextChannels.first { it.id == id }
    }

    /**
     * CYP-869 (OS-B) — archive (remove from the active topology) a DIRECT/GROUP channel and its ACL entries: it
     * disappears from [channels]/[AclMatrix] (no longer listed, unreadable, unwritable) while its messages persist
     * in the store. **Fail-closed:** a [ChannelKind.HUB] spoke is REJECTED (archiving it would break hub-and-spoke
     * coordination — the CYP-49 PO-lockout discipline, at the topology layer). 404 if unknown.
     */
    fun archiveChannel(id: String): Unit = synchronized(lock) {
        val existing = channels.firstOrNull { it.id == id }
            ?: throw NotFoundException("channel '$id' not found", code = "channel_not_found")
        if (existing.kind == ChannelKind.HUB) {
            throw ConflictException(
                "cannot archive hub-and-spoke channel '$id' — it would break coordination",
                code = "channel_hub_protected",
            )
        }
        val nextChannels = channels.filterNot { it.id == id }
        val next = entries.filterNot { it.channelId == id }
        channels = nextChannels
        entries = next
        acl = AclMatrix(nextChannels, next, activeProjectId, sharedInboundChannelIds)
    }

    /**
     * CYP-210 — update an agent's DISPLAY name/color (id is immutable). Pure display fields → NO topology /
     * ACL / matrix impact (name/color are not in [Channel.members] or [AclEntry]); only the [agents] list is
     * rebuilt. A `null` argument leaves that field unchanged (the caller resolves blank→preserve). Returns
     * the updated agent, or null if unknown.
     */
    fun editAgent(id: String, name: String? = null, color: String? = null): Agent? = synchronized(lock) {
        val cur = agents.firstOrNull { it.id == id } ?: return@synchronized null
        // CYP-210 (Test defense-in-depth): blank/null PRESERVE — `ifBlank` here too (not only `?: cur`), so a
        // DIRECT caller passing a blank string can't clear the field where the store's `put` (ifBlank-robust)
        // would preserve it. Parity between the in-memory update and the durable overlay, route or not.
        val next = cur.copy(name = name?.ifBlank { null } ?: cur.name, color = color?.ifBlank { null } ?: cur.color)
        agents = agents.map { if (it.id == id) next else it }
        next
    }

    /**
     * CYP-215 — set (or, with `avatar = null`, CLEAR) an agent's avatar on the in-memory list. Pure display
     * field → NO topology/ACL/matrix impact (same as [editAgent]). Unlike name/color this is a DIRECT set
     * (null genuinely clears — the avatar has an explicit clear path), mirroring [AgentOverrideStore.setAvatar]
     * so the in-memory list and the durable overlay stay in parity. Returns the updated agent, or null if unknown.
     */
    fun setAvatar(id: String, avatar: com.tneff.cyppieagents.model.AgentAvatar?): Agent? = synchronized(lock) {
        val cur = agents.firstOrNull { it.id == id } ?: return@synchronized null
        val next = cur.copy(avatar = avatar)
        agents = agents.map { if (it.id == id) next else it }
        next
    }

    /**
     * Register a new agent at runtime (CYP-97) and add its hub-and-spoke channel with the SAME shape the
     * boot factory [hubAndSpoke] builds, **stamped with [activeProjectId]** (no cross-project leak), then
     * rebuild the matrix. Atomic under the lock.
     *  - WORKER → its spoke `po-<id>` (members `[po, <id>, operator?, PLs]`, read+write, PLs read-only).
     *  - PO (CYP-792) → its op-po ([OP_PO_CHANNEL_ID], DIRECT, {operator, po} both RW) when an operator exists.
     *    A PO is NOT added at runtime in the BOOT project (the call-site add-guard rejects a second PO), but a
     *    non-boot/switched-to project's PO IS (re)added here via [com.tneff.cyppieagents.boot.BootOrchestrator]'s
     *    rehydration, which BYPASSES that guard — so op-po must be seeded here too, else the switched-to PO
     *    window regresses to read-only (CYP-787 symptom). A PO gets NO worker spoke — it is the hub.
     *  - PRODUCT_LEAD → joins every HUB spoke read-only, no spoke of its own.
     */
    fun addAgent(agent: Agent): Agent = synchronized(lock) {
        val po = agents.firstOrNull { it.role == Role.PO }
        val productLeadIds = agents.filter { it.role == Role.PRODUCT_LEAD }.map { it.id } // existing PLs
        agents = agents + agent
        when {
            // CYP-878 — dup-guard, PROJECT-SCOPED (mirrors the op-po branch below): if this worker's spoke
            // `po-<id>` already exists in the active project, skip re-appending it — a plain double-add (or a
            // re-hydration) must NOT silently mint a SECOND `po-<id>` channel (the `spokeChannelFor.firstOrNull`
            // collision). Idempotent: the agent still joins [agents]; the existing spoke stands.
            agent.role == Role.WORKER && po != null &&
                channels.none { it.id == "po-${agent.id}" && it.projectId == activeProjectId } -> {
                // New worker spoke: po + worker + operator? all read+write, plus existing PLs read-only (CYP-98).
                val members = buildList {
                    add(po.id); add(agent.id); operatorId?.let { add(it) }; addAll(productLeadIds)
                }
                val channel = Channel("po-${agent.id}", "po-${agent.id}", ChannelKind.HUB, members, projectId = activeProjectId)
                val newEntries = members.map { m ->
                    AclEntry(channel.id, m, canRead = true, canWrite = m !in productLeadIds, projectId = activeProjectId)
                }
                channels = channels + channel
                entries = entries + newEntries
                acl = AclMatrix(channels, entries, activeProjectId, sharedInboundChannelIds)
            }
            // CYP-792: idempotency is PROJECT-SCOPED — op-po's id is a cross-project CONSTANT and `channels` holds
            // every project's channels un-stashed (rescope isolates by projectId, not by swapping the list, see
            // [rescope]). A plain `id == OP_PO_CHANNEL_ID` check would false-positive on ANOTHER project's op-po
            // and refuse to seed THIS project's — the CYP-81 cross-project-constant-id trap (caught by the e2e
            // switch journey, invisible to a fresh-slice unit test).
            agent.role == Role.PO && operatorId != null &&
                channels.none { it.id == OP_PO_CHANNEL_ID && it.projectId == activeProjectId } -> {
                // CYP-792: a PO added at runtime — a non-boot/switched-to project rehydrated via
                // BootOrchestrator's `state.addAgent` (or a future multi-project create) — gets the SAME op-po
                // the boot factory [hubAndSpoke] seeds. Single-sourced so the switch/rescope path cannot regress
                // to a read-only PO window (the CYP-787 symptom recurring in the S12 multi-project case). DIRECT
                // (not HUB → CYP-111 lockout guard stays clear), members {operator, po}, both canRead+canWrite
                // (the (a)-symmetric grant: operator tasks the PO, the PO's turn-output routes back). Idempotent
                // (skip if op-po already exists for the active project) and only with an operator (nobody to task
                // the PO otherwise). A PO adds NO worker spoke — it is the hub, not a task target of a spoke.
                val channel = Channel(OP_PO_CHANNEL_ID, OP_PO_CHANNEL_ID, ChannelKind.DIRECT, listOf(operatorId, agent.id), projectId = activeProjectId)
                val newEntries = channel.members.map { m ->
                    AclEntry(channel.id, m, canRead = true, canWrite = true, projectId = activeProjectId)
                }
                channels = channels + channel
                entries = entries + newEntries
                acl = AclMatrix(channels, entries, activeProjectId, sharedInboundChannelIds)
            }
            agent.role == Role.PRODUCT_LEAD -> {
                // CYP-98: a runtime-added Product Lead joins every existing HUB spoke as a read-only member
                // (canRead, canWrite=false) and gets NO spoke of its own → never a task target. Fail-closed.
                val spokeIds = channels.filter { it.kind == ChannelKind.HUB }.map { it.id }
                channels = channels.map { ch ->
                    if (ch.kind == ChannelKind.HUB) ch.copy(members = ch.members + agent.id) else ch
                }
                entries = entries + spokeIds.map {
                    AclEntry(it, agent.id, canRead = true, canWrite = false, projectId = activeProjectId)
                }
                acl = AclMatrix(channels, entries, activeProjectId, sharedInboundChannelIds)
            }
        }
        agent
    }

    /**
     * Remove an agent at runtime (CYP-97): drop it from [agents] **and** its spoke channel `po-<id>`
     * **and every ACL entry on that channel** — a clean removal with no dangling channel/ACL — then
     * rebuild the matrix. Atomic under the lock. The remove-guard (the only PO is undeletable) runs at
     * the call site; the agent branch `agent/<id>` is never touched here (worktree fate is the caller's).
     */
    fun removeAgent(id: String): Unit = synchronized(lock) {
        agents = agents.filterNot { it.id == id }
        val spokeId = "po-$id"
        channels = channels.filterNot { it.id == spokeId }
        entries = entries.filterNot { it.channelId == spokeId }
        acl = AclMatrix(channels, entries, activeProjectId, sharedInboundChannelIds)
    }

    /**
     * Re-scope the hub to a new active project (S13 / CYP-102, extended by CYP-246): flip [activeProjectId],
     * **swap in the new project's agent list**, **and rebuild the [AclMatrix]** so every read-path follows the
     * switch. The comm topology (readableChannels/inbox/canRead/canWrite/visibleMessages) re-scopes via the
     * matrix rebuild (channels/entries are project-stamped, CYP-81); `GET /api/agents` / the window set re-scope
     * via the agent-list swap. Atomic under the lock.
     *
     * **CYP-246 — the agent set is per-project.** The CURRENT active [agents] list is stashed under its
     * projectId and the target project's list is restored, or an EMPTY list is minted for a FRESH project
     * (→ 0 agents / 0 windows, the reported expectation). Switching back restores the stashed list. Without
     * the swap the matrix rebuild alone re-scopes the comm reads but leaves the boot project's [agents] live —
     * the exact leak that survived a browser reload in prod (`/api/agents` reads this list, un-rescoped, and
     * [Agent] carries no projectId for the matrix to filter on). [channels]/[entries] are deliberately NOT
     * stashed — the matrix already isolates them by project.
     *
     * Wired from `POST /api/projects/switch` after `ProjectRegistry.setActive`. This partitions the SET only;
     * the per-agent lifecycle/spawn/worktree stay boot-pinned (an agent added in a non-boot project renders
     * but is not yet runnable there) — the full per-project hub/session re-instancing is the deferred S17.
     */
    fun rescope(newProjectId: String): Unit = synchronized(lock) {
        // CYP-246: park the active project's agents and restore the target's (EMPTY for a fresh project) so
        // GET /api/agents / the window set follow the switch. channels/entries stay in the shared,
        // project-stamped list the matrix filters — only the un-scoped agent list needs the swap.
        stashedAgents[activeProjectId] = agents
        agents = stashedAgents.remove(newProjectId) ?: emptyList()
        activeProjectId = newProjectId
        // S17 / CYP-93: the new active project has its OWN inbound shares — recompute before rebuild.
        sharedInboundChannelIds = sharedInboundProvider(newProjectId)
        acl = AclMatrix(channels, entries, newProjectId, sharedInboundChannelIds)
    }

    /**
     * Recompute the active project's inbound shares and rebuild the matrix (S17 / CYP-93) — called after
     * a share is set/revoked so the cross-project permit takes effect without a restart. The share is the
     * gate: a revoke shrinks the set, so the channel falls back to exact-match/fail-closed here, regardless
     * of any lingering AclEntries (§6.4). Atomic under the lock, like [rescope].
     */
    fun refreshShares(): Unit = synchronized(lock) {
        sharedInboundChannelIds = sharedInboundProvider(activeProjectId)
        acl = AclMatrix(channels, entries, activeProjectId, sharedInboundChannelIds)
    }

    /**
     * Ids of the channels the PO is the hub of — every hub-and-spoke spoke ([ChannelKind.HUB]).
     * Computed from the channel kind (set at boot, immutable via the API) rather than current
     * membership, so the membership vector is in scope even though no endpoint mutates members.
     *
     * **S13 / CYP-111 — scoped to the ACTIVE project.** The PO-lockout guard ([setAcl]) compares these
     * ids against the candidate [AclMatrix], which is ACTIVE-scoped; without this filter a *foreign*
     * project's hub channel (e.g. `po-backend` while project A is active) is absent from that matrix and
     * reads as a lockout → a false 409 `po_lockout_protected` on EVERY ACL edit once ≥2 projects exist.
     * Filtering by the active project makes the comparison like-for-like (and excludes cross-project
     * shared-inbound channels, which the active PO is not the hub of). The guard stays sharp for a real
     * lockout in the active project.
     */
    fun poHubChannelIds(): Set<String> =
        channels.filter { it.kind == ChannelKind.HUB && ProjectScope.permits(it.projectId, activeProjectId) }
            .map { it.id }.toSet()

    /**
     * The channel to REACH agent [agentId] — its hub-and-spoke inbound spoke, if present and the agent is
     * a member. A worker's own `po-<id>`; the **PO's** dedicated operator channel [OP_PO_CHANNEL_ID]
     * (CYP-787, symmetric spoke: the PO now has a real spoke like a worker). **Single-sourced:** BOTH the
     * send direction ([com.tneff.cyppieagents.comm.Hub.writableAgents]) and the MediationRouter (the channel
     * an agent's turn-output posts INTO) resolve through here, so for the PO they agree on op-po — the
     * operator tasks the PO AND the PO's reply routes back to op-po (visible to the operator only).
     */
    fun spokeChannelFor(agentId: String): String? {
        val channelId = if (isPoId(agentId)) OP_PO_CHANNEL_ID else "po-$agentId"
        return channels.firstOrNull { it.id == channelId && agentId in it.members }?.id
    }

    /** True iff [agentId] is the PO of the current topology (CYP-787 op-po resolution). */
    private fun isPoId(agentId: String): Boolean = agents.any { it.id == agentId && it.role == Role.PO }

    companion object {
        /** Reserved participant id for the human operator / UI viewer (Spec D2). */
        const val OPERATOR_ID = "operator"

        /**
         * CYP-787 — the id of the PO's dedicated operator↔PO channel (C1/1b). A DIRECT channel (not a HUB
         * spoke) so it is excluded from [poHubChannelIds] and never trips the CYP-111 PO-lockout guard.
         * [spokeChannelFor] resolves the PO to this id (its symmetric inbound/outbound spoke).
         */
        const val OP_PO_CHANNEL_ID = "op-po"

        /**
         * Builds the default hub-and-spoke topology from the agent list (Spec 02 §6.2):
         * one `po-<worker>` channel per worker, both members read+write. PO is in every spoke;
         * a worker only in its own. Hub-and-spoke = this ACL assignment, no special logic.
         *
         * When [operatorId] is given, the operator is added as a **privileged participant** — a
         * member of every channel with read+write — via the SAME ACL entries (no bypass path). That
         * gives the UI viewer the human-in-the-loop view/send while every check still flows through
         * [AclMatrix] (CYP-18).
         *
         * [activeProjectId] (S12 / CYP-81) stamps the generated channels and entries and seeds the
         * state's scope, so the whole topology lives in the one active project (MVP=1).
         */
        fun hubAndSpoke(
            agents: List<Agent>,
            operatorId: String? = null,
            activeProjectId: String = DEFAULT_PROJECT_ID,
            sharedInboundProvider: (String) -> Set<String> = { emptySet() },
        ): HubState {
            val po = agents.firstOrNull { it.role == Role.PO }
                ?: error("hub-and-spoke requires exactly one PO agent")
            val workers = agents.filter { it.role == Role.WORKER }
            // CYP-98: Product Leads are read-only reviewers on every spoke — members (canRead) but NEVER
            // canWrite, and they get NO spoke of their own → structurally never a task target.
            val productLeadIds = agents.filter { it.role == Role.PRODUCT_LEAD }.map { it.id }
            val workerChannels = workers.map { w ->
                val members = buildList {
                    add(po.id); add(w.id)
                    if (operatorId != null) add(operatorId)
                    addAll(productLeadIds)
                }
                Channel(
                    id = "po-${w.id}",
                    name = "po-${w.id}",
                    kind = com.tneff.cyppieagents.model.ChannelKind.HUB,
                    members = members,
                    projectId = activeProjectId,
                )
            }
            // CYP-787 (C1/1b, symmetric): the PO's dedicated operator↔PO channel. DIRECT — NOT a HUB spoke,
            // so it is excluded from [poHubChannelIds] and the CYP-111 PO-lockout guard never trips on it.
            // Members {operator, po}, both canRead+canWrite: the operator TASKS the PO and the PO's turn-output
            // routes BACK here (symmetric spoke, like a worker's po-<id>). Makes [spokeChannelFor] resolve for
            // the PO → "po" ∈ writableAgents (composer-enable) AND MediationRouter posts PO output here instead
            // of dropping it. Narrowly amends CYP-98 "PO=hub, never a task target" to this ONE operator-inbound
            // edge — workers are NOT members, so a worker still cannot task the PO. Seeded only with an operator.
            val opPoChannel = operatorId?.let { op ->
                Channel(
                    id = OP_PO_CHANNEL_ID,
                    name = OP_PO_CHANNEL_ID,
                    kind = com.tneff.cyppieagents.model.ChannelKind.DIRECT,
                    members = listOf(op, po.id),
                    projectId = activeProjectId,
                )
            }
            val channels = workerChannels + listOfNotNull(opPoChannel)
            val workerEntries = workerChannels.flatMap { ch ->
                ch.members.map { member ->
                    // PL read-only posture enforced HERE (core ACL, not just UI): canWrite=false, fail-closed.
                    val readOnly = member in productLeadIds
                    AclEntry(ch.id, member, canRead = true, canWrite = !readOnly, projectId = activeProjectId)
                }
            }
            // CYP-787: op-po ACL — BOTH members (operator, po) canRead+canWrite. This IS the (a)-symmetric
            // grant: the operator tasks the PO and the PO writes its reply back, both directions on the one
            // channel. Explicit uniform RW (not the worker-spoke !readOnly rule) so the PO's intentional write
            // grant here is visible; it is the PO's sole new write edge (no broad grant — workers are not members).
            val opPoEntries = opPoChannel?.let { ch ->
                ch.members.map { member ->
                    AclEntry(ch.id, member, canRead = true, canWrite = true, projectId = activeProjectId)
                }
            }.orEmpty()
            val entries = workerEntries + opPoEntries
            return HubState(agents, channels, entries, activeProjectId, operatorId, sharedInboundProvider)
        }
    }
}
