package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentAvatar
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * CYP-210 — one agent's operator-set overrides (display `name`/`color` + connector `persona`/`launch`).
 * Every field nullable; `null` = no override → the `platform.config.json` seed wins for that field.
 */
@Serializable
data class AgentOverride(
    val name: String? = null,
    val color: String? = null,
    val persona: String? = null,
    val launch: String? = null,
    /**
     * CYP-215 — the per-agent avatar override (Preset or the server-minted Upload `ref`). Set/cleared via
     * [AgentOverrideStore.setAvatar] (NOT the string-merge [AgentOverrideStore.put], which preserves it) —
     * because unlike the string fields the avatar has an explicit CLEAR path (`DELETE .../avatar`). `null`
     * = no override → the client's default. Serialized polymorphically (`{"type":"preset"|"upload",…}`).
     */
    val avatar: AgentAvatar? = null,
)

/**
 * CYP-210 — the durable **overlay** for per-agent customization, out-of-repo under the gitRoot
 * (`.cyppie/agent-overrides.json`, gitignored), keyed `projectId → agentId → [AgentOverride]`.
 *
 * CYP-223 (CYP-220 Phase 1): **store-seam interface;** default impl [FileAgentOverrideStore]; a future PG
 * impl implements this; companion `invoke` = current factory choice, no behavior change.
 */
@com.tneff.cyppieagents.tier.StoreKey("agent_override")
interface AgentOverrideStore {
    /** This agent's override in [projectId], or null (no override → the config seed wins). */
    fun overrideOf(projectId: String, agentId: String): AgentOverride?

    /** All overrides for [projectId] (agentId → override) — applied over the config seed at boot. */
    fun allFor(projectId: String): Map<String, AgentOverride>

    /**
     * Merge a partial edit and persist atomically. **Blank/null fields PRESERVE the stored value** (no
     * blank→null clear — the same data-safety rule as [AgentManagement.edit]); `id` is never a field here
     * (immutable). Returns the merged override.
     */
    fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride

    /**
     * CYP-215 — set (or, with `avatar = null`, CLEAR) an agent's avatar override, preserving the other
     * fields. Distinct from [put]'s blank→preserve rule because the avatar has an explicit clear path
     * (`DELETE .../avatar`): here `null` genuinely clears. Used by the multipart upload (Upload ref), the
     * preset-set edit (Preset), and the clear endpoint (null). Returns the merged override.
     */
    fun setAvatar(projectId: String, agentId: String, avatar: AgentAvatar?): AgentOverride

    /** Drop [agentId]'s override in [projectId] (agent removal). Idempotent. */
    fun removeAgent(projectId: String, agentId: String): Boolean

    /** Cascade: drop ALL of [projectId]'s overrides (the customization partition of a project delete). */
    fun removeProject(projectId: String): Int

    companion object {
        /** Factory seam (CYP-223): the current impl choice is the file store. */
        operator fun invoke(file: File?): AgentOverrideStore = FileAgentOverrideStore(file)
    }
}

/**
 * CYP-220 Phase 6 — the **blank/null-PRESERVES** string-field merge (the [AgentOverrideStore.put] data-safety
 * rule), extracted so the File and Postgres impls apply it from ONE source and cannot drift. The avatar is never
 * touched here (it has its own [AgentOverrideStore.setAvatar] clear path).
 */
internal fun AgentOverride.mergeStringFields(name: String?, color: String?, persona: String?, launch: String?): AgentOverride =
    copy(
        name = name?.ifBlank { null } ?: this.name,
        color = color?.ifBlank { null } ?: this.color,
        persona = persona?.ifBlank { null } ?: this.persona,
        launch = launch?.ifBlank { null } ?: this.launch,
    )

/**
 * It is the
 * OVERRIDE layer over the `platform.config.json` **seed** (the operator's hand-authored file is never
 * rewritten — non-invasive, no clobber). Mirrors [ProjectConfigStore]: a single JSON file, atomic-move
 * write under a lock, per-project keying (so a project cascade-delete drops exactly its entries). Closes the
 * latent gap that runtime name/color/persona/launch edits did NOT survive a restart. Not a credential store;
 * still owner-restricted for hygiene. A null [file] is the in-memory off-switch (tests / dev).
 */
class FileAgentOverrideStore(private val file: File?) : AgentOverrideStore {
    private val log = LoggerFactory.getLogger("boot.agentoverrides")
    private val lock = Any()
    private val byProject = HashMap<String, MutableMap<String, AgentOverride>>()

    init { load() }

    override fun overrideOf(projectId: String, agentId: String): AgentOverride? =
        synchronized(lock) { byProject[projectId]?.get(agentId) }

    override fun allFor(projectId: String): Map<String, AgentOverride> =
        synchronized(lock) { byProject[projectId]?.toMap() ?: emptyMap() }

    override fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride =
        synchronized(lock) {
            val cur = byProject.getOrPut(projectId) { HashMap() }[agentId] ?: AgentOverride()
            val next = cur.mergeStringFields(name, color, persona, launch)
            byProject.getOrPut(projectId) { HashMap() }[agentId] = next
            persist()
            next
        }

    override fun setAvatar(projectId: String, agentId: String, avatar: AgentAvatar?): AgentOverride =
        synchronized(lock) {
            val cur = byProject.getOrPut(projectId) { HashMap() }[agentId] ?: AgentOverride()
            val next = cur.copy(avatar = avatar)
            byProject.getOrPut(projectId) { HashMap() }[agentId] = next
            persist()
            next
        }

    override fun removeAgent(projectId: String, agentId: String): Boolean = synchronized(lock) {
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

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, Map<String, AgentOverride>>>(f.readText())
            synchronized(lock) { decoded.forEach { (p, m) -> byProject[p] = HashMap(m) } }
        }.onFailure { log.warn("agent-overrides load failed ({}) — starting empty", it.message) }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs() // the .cyppie dir is already owner-restricted (0700) by the other stores
        val snapshot: Map<String, Map<String, AgentOverride>> = byProject.mapValues { it.value.toMap() }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
