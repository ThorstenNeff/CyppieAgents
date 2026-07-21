package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * CYP-167 — the durable `(projectId, agentId) → session_id` binding that lets an agent **resume** its
 * Claude-Code conversation after a server restart (Doc 05 D4; spike-verified `--resume` carries context).
 *
 * Server-internal record (no client/wire consumer) → lives entirely in `:server`, no `:core` touch.
 * The key is keyed by **projectId** as well as agentId because projectId is durable across a restart
 * (MVP `"default"` config-derived; multi-project ids are caller-supplied + immutable + persisted in
 * `projects.json`), so the post-restart lookup matches the same stable id it was written under.
 */
@Serializable
data class SessionEntry(
    val projectId: String,
    val agentId: String,
    val sessionId: String,
    val createdAt: Long,
    val lastActivity: Long,
)

/**
 * Persistence seam, mirroring [com.tneff.cyppieagents.comm.MessageStore]: swappable In-Memory ↔ JSON
 * without touching the connector. Implementations must be safe for concurrent calls.
 */
@com.tneff.cyppieagents.tier.StoreKey("session")
interface SessionStore {
    /** The durable binding for [projectId]/[agentId], or null ⇒ no `--resume` (first-start invariant). */
    fun find(projectId: String, agentId: String): SessionEntry?

    /** Write the binding AFTER `system/init` delivered the id (write-after-init). Idempotent per key. */
    fun upsert(projectId: String, agentId: String, sessionId: String, now: Long)

    /** Drop the binding (stale-resume fallback): the next spawn then starts fresh, no `--resume`. */
    fun clear(projectId: String, agentId: String)
}

/** In-memory store; one entry per `(projectId, agentId)`. */
open class InMemorySessionStore : SessionStore {
    private val lock = Any()
    // Keyed by the (projectId, agentId) Pair — collision-proof for any id content and keeps the source a
    // plain-text .kt (no in-string separator byte for diff/review tooling to choke on).
    private val entries = LinkedHashMap<Pair<String, String>, SessionEntry>()

    override fun find(projectId: String, agentId: String): SessionEntry? = synchronized(lock) {
        entries[projectId to agentId]
    }

    override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) = synchronized(lock) {
        val k = projectId to agentId
        // createdAt is preserved only while the SAME session id is rewritten (e.g. a lastActivity refresh);
        // a NEW id is a new conversation, so its createdAt resets to now.
        val keepCreated = entries[k]?.takeIf { it.sessionId == sessionId }?.createdAt ?: now
        entries[k] = SessionEntry(projectId, agentId, sessionId, keepCreated, now)
    }

    override fun clear(projectId: String, agentId: String) {
        synchronized(lock) { entries.remove(projectId to agentId) }
    }

    /** Snapshot of all entries (used by the JSON store to persist). */
    protected fun snapshot(): List<SessionEntry> = synchronized(lock) { entries.values.toList() }

    protected fun loadAll(initial: List<SessionEntry>) = synchronized(lock) {
        entries.clear()
        initial.forEach { entries[it.projectId to it.agentId] = it }
    }
}

/**
 * JSON-file backed store: keeps the in-memory index hot and flushes the full entry list to [file] on
 * every mutation, so bindings survive a server restart (1:1 with [com.tneff.cyppieagents.comm
 * .JsonFileMessageStore]). On construction it loads any existing file; a torn/corrupt file is backed
 * up and the store starts empty, so a bad file can never brick the boot (the resume just won't fire).
 */
class JsonFileSessionStore(private val file: File) : InMemorySessionStore() {
    private val flushLock = Any()
    private val log = LoggerFactory.getLogger("connector.sessionstore")

    init {
        if (file.exists() && file.length() > 0) {
            try {
                loadAll(CommJson.decodeFromString<List<SessionEntry>>(file.readText()))
            } catch (e: Exception) {
                val backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
                runCatching { file.copyTo(backup, overwrite = true) }
                log.error("corrupt session store at {}; backed up to {} and starting empty", file, backup, e)
            }
        }
    }

    override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) {
        super.upsert(projectId, agentId, sessionId, now)
        flush()
    }

    override fun clear(projectId: String, agentId: String) {
        super.clear(projectId, agentId)
        flush()
    }

    private fun flush() = synchronized(flushLock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot()))
        // Atomic replace: a crash mid-flush leaves either the old or the new file intact, never a
        // half-written one. Fall back to a plain replace only where atomic moves are unsupported.
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
