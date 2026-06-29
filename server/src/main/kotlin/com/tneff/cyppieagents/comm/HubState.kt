package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclGuard
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.ProjectScope
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ConflictException

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
     */
    fun setAcl(entry: AclEntry): AclEntry = synchronized(lock) {
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
     * Register a new agent at runtime (CYP-97) and, for a WORKER, add its hub-and-spoke spoke
     * `po-<id>` with the SAME shape the boot factory builds: members `[po, <id>, operator?]`, all
     * read+write, **stamped with [activeProjectId]** (no cross-project leak), and rebuild the matrix.
     * Atomic under the lock. The add-guard (exactly-one-PO / unique id) runs at the call site before
     * this; a PO is never added at runtime (so no second hub), and this only ever creates a spoke.
     */
    fun addAgent(agent: Agent): Agent = synchronized(lock) {
        val po = agents.firstOrNull { it.role == Role.PO }
        val productLeadIds = agents.filter { it.role == Role.PRODUCT_LEAD }.map { it.id } // existing PLs
        agents = agents + agent
        when {
            agent.role == Role.WORKER && po != null -> {
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
     * Re-scope the hub to a new active project (S13 / CYP-102): flip [activeProjectId] **and rebuild
     * the [AclMatrix]** so every comm read-path (readableChannels/inbox/canRead/canWrite/visibleMessages)
     * filters to the new project — the same chokepoint (CYP-81), now following the live active pointer.
     * Atomic under the lock; the matrix rebuild is what makes the switch take effect, so it must run
     * together with the pointer flip (a flip without rebuild would leave the OLD project's view live).
     *
     * Wired from `POST /api/projects/switch` after `ProjectRegistry.setActive`. Today one HubState holds
     * the active project's topology, so re-scoping to a project with no live channels yields an empty
     * view (honest); per-project hub/session re-instancing is S17.
     */
    fun rescope(newProjectId: String): Unit = synchronized(lock) {
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

    /** The agent's hub-and-spoke channel (`po-<agentId>`), if present and the agent is a member. */
    fun spokeChannelFor(agentId: String): String? =
        channels.firstOrNull { it.id == "po-$agentId" && agentId in it.members }?.id

    companion object {
        /** Reserved participant id for the human operator / UI viewer (Spec D2). */
        const val OPERATOR_ID = "operator"

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
            val channels = workers.map { w ->
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
            val entries = channels.flatMap { ch ->
                ch.members.map { member ->
                    // PL read-only posture enforced HERE (core ACL, not just UI): canWrite=false, fail-closed.
                    val readOnly = member in productLeadIds
                    AclEntry(ch.id, member, canRead = true, canWrite = !readOnly, projectId = activeProjectId)
                }
            }
            return HubState(agents, channels, entries, activeProjectId, operatorId, sharedInboundProvider)
        }
    }
}
