package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CompactConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * CYP-326 — the persisted compact-orchestration config (the operator's "compact allowed" checkbox + the
 * configurable threshold), out-of-repo under the gitRoot (`.cyppie/compact-config.json`, gitignored), keyed by
 * `projectId`. Mirrors [FileTokenUsageStore] (CYP-223 seam): File impl now, a PG impl later behind the
 * interface. Fail-closed default ([CompactConfig] `allowed=false`) for an unset project. A null [file] is the
 * in-memory off-switch (tests/dev).
 */
interface CompactConfigStore {
    /** The config for [projectId], or the fail-closed default (`allowed=false`, 500K) if unset. */
    fun get(projectId: String): CompactConfig

    /** Persist [config] for [projectId] atomically. */
    fun set(projectId: String, config: CompactConfig)

    companion object {
        operator fun invoke(file: File?): CompactConfigStore = FileCompactConfigStore(file)
    }
}

class FileCompactConfigStore(private val file: File?) : CompactConfigStore {
    private val log = LoggerFactory.getLogger("boot.compactconfig")
    private val lock = Any()
    private val byProject = HashMap<String, CompactConfig>()

    init { load() }

    override fun get(projectId: String): CompactConfig =
        synchronized(lock) { byProject[projectId] ?: CompactConfig() } // default = fail-closed (allowed=false)

    override fun set(projectId: String, config: CompactConfig) = synchronized(lock) {
        if (byProject.put(projectId, config) != config) persist()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, CompactConfig>>(f.readText())
            synchronized(lock) { byProject.putAll(decoded) }
        }.onFailure { log.warn("compact-config load failed ({}) — starting with defaults", it.message) }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val snapshot: Map<String, CompactConfig> = byProject.toMap()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
