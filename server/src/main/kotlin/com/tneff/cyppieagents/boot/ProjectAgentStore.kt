package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.MigrationTarget
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * CYP-256 (CYP-247.5) — the durable record of a **runtime-added** agent: its EXISTENCE + full config, so a
 * non-boot project's agents survive a restart (the gap `AgentOverrideStore` did NOT fill — that persists per-
 * agent *edits* on top of the config seed, not agent *existence*). One row rehydrates BOTH the [HubState]
 * slice entry (topology) AND the [AgentConfigRegistry] entry (launch/persona/kind) — [toAgent] + the config
 * fields.
 *
 * **Secret-free** (identity/config only): a remote agent's *token* lives in [RemoteTokenStore] (CYP-171), NEVER
 * here — so this store follows the CYP-220 non-secret residency tier (migratable). .5a only ever stores LOCAL
 * (`remote=false`) agents; per-project remote-agent durability (its token is `RemoteTokenStore`-global today, not
 * per-project) couples to the deferred remote path (CYP-264).
 */
@Serializable
data class StoredAgent(
    val id: String,
    val name: String,
    val role: Role,
    val worktree: String,
    val launch: String,
    val persona: String? = null,
    val connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    val color: String? = null,
    val avatar: AgentAvatar? = null,
    val remote: Boolean = false,
) {
    /** Rehydrate the [HubState] topology entry — STOPPED (start is the CYP-73 lifecycle, same as `add`). */
    fun toAgent(): Agent = Agent(
        id = id, name = name, role = role, worktree = worktree, runState = AgentRunState.STOPPED,
        connectorKind = connectorKind, color = color, avatar = avatar,
    )

    companion object {
        /** Compose the durable record from the live [Agent] + its [AgentRuntimeConfig] (launch/persona/kind).
         *  CYP-172 Part 2: `remote` is a boot/spec-level flag (NOT on the runtime [Agent]), so a re-put (edit /
         *  avatar) MUST be told it explicitly — else it silently resets a persisted remote agent to `remote=false`,
         *  which on the next rehydration flips it to a LOCAL, un-clamped agent (the all-or-nothing hole). Callers
         *  pass `id in remoteAgents`. */
        fun of(a: Agent, cfg: AgentRuntimeConfig?, remote: Boolean = false): StoredAgent = StoredAgent(
            id = a.id, name = a.name, role = a.role, worktree = a.worktree,
            launch = cfg?.launch ?: "claude", persona = cfg?.persona,
            connectorKind = cfg?.connectorKind ?: a.connectorKind,
            color = a.color, avatar = a.avatar, remote = remote,
        )
    }
}

/**
 * CYP-256 — the per-project agent-set store, on the CYP-220 store-seam (cf. [AgentOverrideStore]): interface +
 * companion `invoke` factory (current choice = [FileProjectAgentStore]) + a Postgres impl later (.5c). Keyed
 * `projectId → agentId` so a project cascade-delete drops exactly its rows (fail-closed on a blank projectId).
 * [contains] is the D1 single-source discriminator: `AgentManagement` routes a runtime-added agent's persist
 * HERE, a config-seeded boot agent's to the override overlay — no double-write.
 */
interface ProjectAgentStore {
    /** All runtime-added agents of [projectId], for boot/switch rehydration. Deterministic order (by id). */
    fun agentsFor(projectId: String): List<StoredAgent>

    /** True if [agentId] is a runtime-added agent of [projectId] (the D1 route discriminator). */
    fun contains(projectId: String, agentId: String): Boolean

    /** Add or replace an agent's full record. Persisted atomically; a write failure THROWS (never swallowed). */
    fun put(projectId: String, agent: StoredAgent)

    /** Drop [agentId] from [projectId] (agent removal). Idempotent. */
    fun remove(projectId: String, agentId: String): Boolean

    /** Cascade: drop ALL of [projectId]'s agents (the agent-set partition of a project delete). */
    fun removeProject(projectId: String): Int

    companion object {
        /** Factory seam (CYP-223 pattern): the current impl choice is the file store. */
        operator fun invoke(file: File?): ProjectAgentStore = FileProjectAgentStore(file)
    }
}

/** One canonical migration row = a (projectId, agent) pair — the unit [MigrationTarget] copies + checksums. */
@Serializable
private data class ProjectAgentRow(val projectId: String, val agent: StoredAgent)

/**
 * The File impl: a single JSON file (`.cyppie/project-agents.json`), out-of-repo under the gitRoot, atomic-move
 * write under a lock, per-project keying — mirrors [FileAgentOverrideStore]. A null [file] is the in-memory
 * off-switch (tests / dev). Also a [MigrationTarget] (`exportRows`/`importRows`) so it joins the CYP-220
 * Postgres migration via `StoreMigrator`. **No monotonic counter** (a pure keyed set), so the "realign the
 * open-time counter in importRows" rule does not apply here; the roundtrip tooth still loads a FRESH instance.
 */
class FileProjectAgentStore(private val file: File?) : ProjectAgentStore, MigrationTarget {
    private val log = LoggerFactory.getLogger("boot.projectagents")
    private val lock = Any()
    private val byProject = HashMap<String, MutableMap<String, StoredAgent>>()

    init { load() }

    override fun agentsFor(projectId: String): List<StoredAgent> =
        synchronized(lock) { byProject[projectId]?.values?.sortedBy { it.id } ?: emptyList() }

    override fun contains(projectId: String, agentId: String): Boolean =
        synchronized(lock) { byProject[projectId]?.containsKey(agentId) == true }

    override fun put(projectId: String, agent: StoredAgent): Unit = synchronized(lock) {
        byProject.getOrPut(projectId) { HashMap() }[agent.id] = agent
        persist() // atomic; throws on failure (CR3 — never swallowed → the caller/route surfaces it)
    }

    override fun remove(projectId: String, agentId: String): Boolean = synchronized(lock) {
        val removed = byProject[projectId]?.remove(agentId) != null
        if (removed) persist()
        removed
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized 0 // fail-closed: never an unscoped clear
        val n = byProject.remove(projectId)?.size ?: 0
        if (n > 0) persist()
        n
    }

    // ---- CYP-220 MigrationTarget (canonical rows, sorted for a deterministic checksum) ----

    override fun exportRows(): List<ByteArray> = synchronized(lock) {
        byProject.entries.sortedBy { it.key }.flatMap { (pid, m) ->
            m.values.sortedBy { it.id }.map {
                CommJson.encodeToString(ProjectAgentRow.serializer(), ProjectAgentRow(pid, it)).encodeToByteArray()
            }
        }
    }

    override fun importRows(rows: List<ByteArray>): Unit = synchronized(lock) {
        byProject.clear()
        rows.forEach { r ->
            val row = CommJson.decodeFromString(ProjectAgentRow.serializer(), r.decodeToString())
            byProject.getOrPut(row.projectId) { HashMap() }[row.agent.id] = row.agent
        }
        persist()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, Map<String, StoredAgent>>>(f.readText())
            synchronized(lock) { decoded.forEach { (p, m) -> byProject[p] = HashMap(m) } }
        }.onFailure { log.warn("project-agents load failed ({}) — starting empty", it.message) }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val snapshot: Map<String, Map<String, StoredAgent>> = byProject.mapValues { it.value.toMap() }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
