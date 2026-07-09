package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * CYP-325 (defect 2) — the durable overlay for each agent's **last context-token value** (the `/ws/token-usage`
 * title-bar number), out-of-repo under the gitRoot (`.cyppie/token-usage.json`, gitignored), keyed
 * `projectId → agentId → contextTokens`. Closes the gap that the number lived only in-memory → vanished on a
 * server (deploy) restart and for an agent with no fresh turn; on boot the tracker rehydrates from here so
 * snapshot-on-connect delivers it again, refreshed by the next turn.
 *
 * ONLY trustworthy numbers are stored — a `null` (unknown: Connector-B / a reset agent) is REMOVED, never
 * persisted as a value (the same null≠0 honesty as the feed). Busy-state is deliberately NOT persisted.
 *
 * Store-seam interface (CYP-223 pattern, mirroring [AgentOverrideStore]): the File impl is the current
 * factory choice; a future PG impl implements this interface behind the same seam — no behaviour change.
 */
interface TokenUsageStore {
    /** Every persisted last-context-token value for [projectId] (agentId → tokens) — the boot rehydration set. */
    fun allFor(projectId: String): Map<String, Int>

    /** Persist [agentId]'s newest value; `null` (unknown) REMOVES it (unknown is never stored). Idempotent. */
    fun put(projectId: String, agentId: String, contextTokens: Int?)

    /** Drop [agentId]'s value in [projectId] (agent removal). Idempotent. */
    fun removeAgent(projectId: String, agentId: String)

    /** Cascade: drop ALL of [projectId]'s values (the token partition of a project delete). Returns the count. */
    fun removeProject(projectId: String): Int

    companion object {
        /** Factory seam (CYP-223): the current impl choice is the file store. `null` file = in-memory (tests). */
        operator fun invoke(file: File?): TokenUsageStore = FileTokenUsageStore(file)
    }
}

/**
 * A single JSON file, atomic-move write under a lock, per-project keying (so a project cascade-delete drops
 * exactly its entries). Mirrors [FileAgentOverrideStore]. A null [file] is the in-memory off-switch (tests/dev).
 */
class FileTokenUsageStore(private val file: File?) : TokenUsageStore {
    private val log = LoggerFactory.getLogger("boot.tokenusage")
    private val lock = Any()
    private val byProject = HashMap<String, MutableMap<String, Int>>()

    init { load() }

    override fun allFor(projectId: String): Map<String, Int> =
        synchronized(lock) { byProject[projectId]?.toMap() ?: emptyMap() }

    override fun put(projectId: String, agentId: String, contextTokens: Int?) = synchronized(lock) {
        if (contextTokens == null) {
            if (byProject[projectId]?.remove(agentId) != null) persist() // unknown → not persisted
        } else {
            val prev = byProject.getOrPut(projectId) { HashMap() }.put(agentId, contextTokens)
            if (prev != contextTokens) persist() // de-dup unchanged
        }
    }

    override fun removeAgent(projectId: String, agentId: String) = synchronized(lock) {
        if (byProject[projectId]?.remove(agentId) != null) persist()
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
            val decoded = CommJson.decodeFromString<Map<String, Map<String, Int>>>(f.readText())
            synchronized(lock) { decoded.forEach { (p, m) -> byProject[p] = HashMap(m) } }
        }.onFailure { log.warn("token-usage load failed ({}) — starting empty", it.message) }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs() // the .cyppie dir is already owner-restricted (0700) by the other stores
        val snapshot: Map<String, Map<String, Int>> = byProject.mapValues { it.value.toMap() }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
