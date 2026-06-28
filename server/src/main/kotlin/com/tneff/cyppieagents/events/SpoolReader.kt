package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Hook-spool ingestion (PRD §3.6, ST4/CYP-38). Hooks (PreCompact/PostCompact/SessionStart) are shell
 * scripts OUTSIDE the stream-json channel; they never touch the DB. Each appends a JSONL line (with a
 * `sourceTs`) to a spool file. This reader **tails** the spool and projects each complete line into a
 * content-free `hook.fired` [EventDraft]; the caller (the mediator, CYP-37) records it, where it gets
 * its authoritative `ts`/`seq` — `sourceTs` is preserved as informative only.
 *
 * **Idempotent (PRD §3.6):** a persisted byte-offset marker means a re-tail after a restart never
 * re-emits already-processed lines. Only complete (newline-terminated) lines are consumed; a trailing
 * partial line (a hook mid-write) is left for the next tail. A bad/garbled line is skipped, never fatal.
 *
 * **Metadata-only (PRD §2/§3.5):** only a whitelist (`name`, `outcome`) is lifted into `detail` — the
 * raw line is never copied wholesale, so a hook can't smuggle content into the log.
 */
class SpoolReader(
    private val spoolPath: Path,
    private val offsetPath: Path = Path.of("$spoolPath.offset"),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger("events.spool")
    private val mutex = Mutex()
    private var offset: Long = loadOffset()

    /** Read complete lines appended since the last call (or restart), as `hook.fired` drafts. */
    suspend fun readNew(): List<EventDraft> = withContext(io) {
        mutex.withLock {
            val file = spoolPath.toFile()
            if (!file.exists()) return@withLock emptyList()
            val len = file.length()
            // File shrank (truncated/replaced) → our offset is stale; re-read from the start.
            if (len < offset) offset = 0L
            if (len == offset) return@withLock emptyList()

            val chunk = ByteArray((len - offset).toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(chunk)
            }
            val text = String(chunk, StandardCharsets.UTF_8)
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline < 0) return@withLock emptyList() // no complete line yet

            val complete = text.substring(0, lastNewline)
            // Advance past every complete line consumed, plus the final '\n' (UTF-8 round-trips exactly).
            val consumed = complete.toByteArray(StandardCharsets.UTF_8).size + 1
            val drafts = complete.split('\n').mapNotNull { line -> parseLine(line) }

            offset += consumed
            persistOffset(offset)
            drafts
        }
    }

    private fun parseLine(line: String): EventDraft? {
        if (line.isBlank()) return null
        val obj = runCatching { CommJson.decodeFromString(JsonObject.serializer(), line) }.getOrNull()
        if (obj == null) {
            log.warn("skipping unparseable spool line")
            return null
        }
        val name = obj.str("name") ?: run {
            log.warn("skipping spool line without a hook name")
            return null
        }
        val outcome = obj.str("outcome")
        return EventDraft(
            agentId = obj.str("agentId") ?: PLATFORM,
            // S12 / CYP-83: prefer the new `projectId` key, accept the legacy `teamId` from older hook
            // spools, else the PLATFORM sentinel (platform-internal hook event, no specific project).
            projectId = obj.str("projectId") ?: obj.str("teamId") ?: PLATFORM,
            type = EventType.HOOK_FIRED,
            severity = if (outcome.equals("error", ignoreCase = true)) Severity.WARN else Severity.INFO,
            sessionId = obj.str("sessionId"),
            sourceTs = obj.long("sourceTs"), // preserved; ts/seq are assigned at append
            // Whitelist only — never copy the raw line (metadata-only, structurally content-free).
            detail = buildJsonObject {
                put("name", name)
                if (outcome != null) put("outcome", outcome)
            },
        )
    }

    private fun loadOffset(): Long =
        runCatching { offsetPath.toFile().takeIf { it.exists() }?.readText()?.trim()?.toLong() }
            .getOrNull() ?: 0L

    private fun persistOffset(value: Long) {
        val target = offsetPath.toFile()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(value.toString())
        try {
            Files.move(tmp.toPath(), offsetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), offsetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val PLATFORM = "platform"

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
