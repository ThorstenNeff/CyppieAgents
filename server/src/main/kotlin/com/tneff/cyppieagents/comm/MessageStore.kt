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
}

/** In-memory store; insertion order preserved. */
open class InMemoryMessageStore : MessageStore {
    private val lock = Any()
    private val messages = mutableListOf<Message>()
    private var nextSeq = 1L // CYP-705: the in-memory analog of the SQLite AUTOINCREMENT `seq`.

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

    /** Snapshot of all messages (used by the JSON store to persist). */
    protected fun snapshot(): List<Message> = synchronized(lock) { messages.toList() }

    protected fun loadAll(initial: List<Message>) = synchronized(lock) {
        messages.clear(); messages.addAll(initial)
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
