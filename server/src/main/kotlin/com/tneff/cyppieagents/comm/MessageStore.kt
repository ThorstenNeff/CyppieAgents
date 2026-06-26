package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Message
import java.io.File

/**
 * Persistence seam (Spec 02 §15): swappable behind this interface (In-Memory ↔ JSON ↔ later
 * SQLDelight) without touching the hub/REST. Implementations must be safe for concurrent calls.
 */
interface MessageStore {
    fun append(message: Message)
    /** Messages in one channel, optionally only those strictly after [since] (epoch ms). */
    fun byChannel(channelId: String, since: Long? = null): List<Message>
    /** Messages across several channels (for inbox aggregation), optionally after [since]. */
    fun acrossChannels(channelIds: Collection<String>, since: Long? = null): List<Message>
}

/** In-memory store; insertion order preserved. */
open class InMemoryMessageStore : MessageStore {
    private val lock = Any()
    private val messages = mutableListOf<Message>()

    override fun append(message: Message) {
        synchronized(lock) { messages.add(message) }
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
    }
}

/**
 * JSON-file backed store: keeps the in-memory index hot and flushes the full message list to
 * [file] on every append, so messages survive a server restart (Spec 02 §15 / Slice S4 AC).
 * On construction it loads any existing file.
 */
class JsonFileMessageStore(private val file: File) : InMemoryMessageStore() {
    private val flushLock = Any()

    init {
        if (file.exists() && file.length() > 0) {
            val loaded = CommJson.decodeFromString<List<Message>>(file.readText())
            loadAll(loaded)
        }
    }

    override fun append(message: Message) {
        super.append(message)
        flush()
    }

    private fun flush() = synchronized(flushLock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshot()))
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}
