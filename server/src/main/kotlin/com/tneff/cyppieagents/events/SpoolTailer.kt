package com.tneff.cyppieagents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Tails the hook spool (PRD §3.6, the mediator's one-source ingestion): periodically calls
 * [SpoolReader.readNew] and records the resulting `hook.fired` drafts into the Event-Log. The reader
 * and recorder come from CYP-38/CYP-35; this is just the loop.
 *
 * **N1 — delivery semantics = AT-MOST-ONCE (PO decision, CYP-38 review).** [SpoolReader.readNew]
 * commits its byte-offset marker BEFORE these drafts are recorded, so a re-tail never duplicates. The
 * trade is that a crash in the gap between offset-commit and [EventRecorder.record] loses those rare
 * hook events — acceptable for MVP: hooks are low-frequency, the window is narrow, and the key ones
 * are also derivable from `compact.triggered`/`compact.completed` in the stream. At-least-once
 * (record-then-commit, with replay de-dup) is a later follow-up, not MVP.
 */
class SpoolTailer(
    private val reader: SpoolReader,
    private val recorder: EventRecorder,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val log = LoggerFactory.getLogger("events.spool.tailer")
    private var job: Job? = null

    fun start() {
        check(job == null) { "SpoolTailer already started" }
        job = scope.launch {
            while (isActive) {
                // at-most-once: readNew() advances the offset, then we record (see class doc / N1).
                runCatching { reader.readNew().forEach { recorder.record(it) } }
                    .onFailure { log.warn("spool tail iteration failed", it) }
                delay(intervalMs)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}
