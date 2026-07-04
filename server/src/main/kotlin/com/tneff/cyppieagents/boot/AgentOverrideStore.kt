package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
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
)

/**
 * CYP-210 — the durable **overlay** for per-agent customization, out-of-repo under the gitRoot
 * (`.cyppie/agent-overrides.json`, gitignored), keyed `projectId → agentId → [AgentOverride]`. It is the
 * OVERRIDE layer over the `platform.config.json` **seed** (the operator's hand-authored file is never
 * rewritten — non-invasive, no clobber). Mirrors [ProjectConfigStore]: a single JSON file, atomic-move
 * write under a lock, per-project keying (so a project cascade-delete drops exactly its entries). Closes the
 * latent gap that runtime name/color/persona/launch edits did NOT survive a restart. Not a credential store;
 * still owner-restricted for hygiene. A null [file] is the in-memory off-switch (tests / dev).
 */
class AgentOverrideStore(private val file: File?) {
    private val log = LoggerFactory.getLogger("boot.agentoverrides")
    private val lock = Any()
    private val byProject = HashMap<String, MutableMap<String, AgentOverride>>()

    init { load() }

    /** This agent's override in [projectId], or null (no override → the config seed wins). */
    fun overrideOf(projectId: String, agentId: String): AgentOverride? =
        synchronized(lock) { byProject[projectId]?.get(agentId) }

    /** All overrides for [projectId] (agentId → override) — applied over the config seed at boot. */
    fun allFor(projectId: String): Map<String, AgentOverride> =
        synchronized(lock) { byProject[projectId]?.toMap() ?: emptyMap() }

    /**
     * Merge a partial edit and persist atomically. **Blank/null fields PRESERVE the stored value** (no
     * blank→null clear — the same data-safety rule as [AgentManagement.edit]); `id` is never a field here
     * (immutable). Returns the merged override.
     */
    fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride =
        synchronized(lock) {
            val cur = byProject.getOrPut(projectId) { HashMap() }[agentId] ?: AgentOverride()
            val next = cur.copy(
                name = name?.ifBlank { null } ?: cur.name,
                color = color?.ifBlank { null } ?: cur.color,
                persona = persona?.ifBlank { null } ?: cur.persona,
                launch = launch?.ifBlank { null } ?: cur.launch,
            )
            byProject.getOrPut(projectId) { HashMap() }[agentId] = next
            persist()
            next
        }

    /** Drop [agentId]'s override in [projectId] (agent removal). Idempotent. */
    fun removeAgent(projectId: String, agentId: String): Boolean = synchronized(lock) {
        val removed = byProject[projectId]?.remove(agentId) != null
        if (removed) persist()
        removed
    }

    /** Cascade: drop ALL of [projectId]'s overrides (the customization partition of a project delete). */
    fun removeProject(projectId: String): Int = synchronized(lock) {
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
