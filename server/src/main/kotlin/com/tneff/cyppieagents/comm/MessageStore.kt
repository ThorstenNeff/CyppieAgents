package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Message
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistence seam (Spec 02 §15): swappable behind this interface (In-Memory ↔ JSON ↔ later
 * SQLDelight) without touching the hub/REST. Implementations must be safe for concurrent calls.
 */
interface MessageStore {
    /** Persist [message] and return the stored copy with its store-assigned [Message.seq] (CYP-705). */
    fun append(message: Message): Message
    /** Messages in one channel, optionally only those strictly after [since] (epoch ms). Seq-ascending. */
    fun byChannel(channelId: String, since: Long? = null): List<Message>
    /** Messages across several channels (for inbox aggregation), optionally after [since]. */
    fun acrossChannels(channelIds: Collection<String>, since: Long? = null): List<Message>

    /**
     * CYP-905 (Parity-Edit E-server) — replace message [id]'s **body** in place with [newBody] and record
     * [editedAt] (epoch ms). Returns the updated stored copy (`seq`/`channelId`/`from`/`ts`/`meta`/`projectId`
     * unchanged, only `body` replaced), or `null` if [id] is absent. The edit MARKER ([editedAt]) is stored
     * **out-of-band** — NEVER on [Message] — so the `/ws/hub` `WireMessage(message)` stays byte-clean; recover it
     * via [editedAtOf]. The edited body DOES ride `message.body` (agents see the current text). Concurrent-safe.
     */
    fun update(id: String, newBody: String, editedAt: Long): Message?

    /**
     * CYP-905 — the out-of-band edit timestamps (epoch ms) for [ids]; entries absent from the map were never
     * edited. **Batched** so a channel read resolves all its markers in ONE call (no per-message round-trip).
     */
    fun editedAtOf(ids: Collection<String>): Map<String, Long>
}

/** In-memory store; insertion order preserved. */
open class InMemoryMessageStore : MessageStore {
    private val lock = Any()
    private val messages = mutableListOf<Message>()
    private var nextSeq = 1L // CYP-705: the in-memory analog of the SQLite AUTOINCREMENT `seq`.
    // CYP-905: out-of-band edit markers (message id → epoch ms). Kept off [Message] so the /ws/hub wire stays clean.
    private val editedAt = HashMap<String, Long>()

    override fun append(message: Message): Message = synchronized(lock) {
        val stored = message.copy(seq = nextSeq++)
        messages.add(stored)
        stored
    }

    override fun byChannel(channelId: String, since: Long?): List<Message> = synchronized(lock) {
        messages.filter { it.channelId == channelId && (since == null || it.ts > since) }
    }

    override fun acrossChannels(channelIds: Collection<String>, since: Long?): List<Message> = synchronized(lock) {
        val set = channelIds.toSet()
        messages.filter { it.channelId in set && (since == null || it.ts > since) }
    }

    override fun update(id: String, newBody: String, editedAt: Long): Message? = synchronized(lock) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized null
        val updated = messages[idx].copy(body = newBody) // body in-place; seq/from/ts/meta/projectId unchanged
        messages[idx] = updated
        this.editedAt[id] = editedAt
        updated
    }

    override fun editedAtOf(ids: Collection<String>): Map<String, Long> = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized emptyMap()
        val set = ids.toSet()
        editedAt.filterKeys { it in set }
    }

    /** Snapshot of all messages (used by the JSON store to persist). */
    protected fun snapshot(): List<Message> = synchronized(lock) { messages.toList() }

    protected fun loadAll(initial: List<Message>) = synchronized(lock) {
        messages.clear(); messages.addAll(initial)
        // CYP-905: markers are out-of-band; a reloaded snapshot carries none (the JSON store is legacy — it does
        // not persist markers; the durable prod store SQLite persists its own `edited_at` column).
        editedAt.clear()
        // CYP-705: resume the seq counter past any persisted seq so new appends stay monotonic across restart.
        nextSeq = (initial.maxOfOrNull { it.seq } ?: 0L) + 1L
    }
}

/**
 * JSON-file backed store: keeps the in-memory index hot and flushes the full message list to
 * [file] on every append, so messages survive a server restart (Spec 02 §15 / Slice S4 AC).
 * On construction it loads any existing file.
 */
class JsonFileMessageStore(private val file: File) : InMemoryMessageStore() {
    private val flushLock = Any()
    private val log = LoggerFactory.getLogger("comm.store")

    init {
        if (file.exists() && file.length() > 0) {
            try {
                loadAll(CommJson.decodeFromString<List<Message>>(file.readText()))
            } catch (e: Exception) {
                // A torn/corrupt file must NOT brick the boot (S4 AC "survives restart"). Back it
                // up for forensics and start empty rather than crashing on decode.
                val backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
                runCatching { file.copyTo(backup, overwrite = true) }
                log.error("corrupt message store at {}; backed up to {} and starting empty", file, backup, e)
            }
        }
    }

    override fun append(message: Message): Message {
        val stored = super.append(message)
        flush()
        return stored
    }

    override fun update(id: String, newBody: String, editedAt: Long): Message? {
        // CYP-905: the edited BODY persists via the snapshot flush (same as [append]). The editedAt MARKER is
        // process-lifetime on this LEGACY JSON store — it is not part of the persisted `List<Message>` snapshot;
        // the durable prod store (SQLite) persists the marker in its own `edited_at` column.
        val updated = super.update(id, newBody, editedAt) ?: return null
        flush()
        return updated
    }

    private fun flush() = synchronized(flushLock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot()))
        // Atomic replace: a crash mid-flush leaves either the old or the new file intact, never
        // a half-written one (B1). Fall back to a plain replace only where atomic moves are unsupported.
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
