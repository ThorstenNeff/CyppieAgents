package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The **Sense** stage of the Mediator-Aufsicht (07 §2/§3): a reacting consumer of the Event-Log bus.
 * It `subscribe`s to the [EventSink] (06 §2) — **not** to the Mediator — so it is host-agnostic the
 * moment several mediators feed one stream, and every produced [Signal] is emitted back into the same
 * bus via [SignalSink].
 *
 * Scope of authority (07 §1): **read the stream + emit signals, nothing more.** The Scanner holds no
 * connector/session reference; the only thing it can do besides reading is hand a [Signal] to the
 * [SignalSink]. Deciding and acting on an agent is the Warden's job in a later slice.
 *
 * This is the **scaffold** (CYP-60): the generic frame plus the plugin seam. It ships with whatever
 * [detectors] are registered (empty by default); the first one — the stall detector — arrives in
 * CYP-61 as a new entry in this list, with no change to the frame.
 */
class Scanner(
    private val sink: EventSink,
    private val detectors: List<Detector>,
    private val signals: SignalSink,
    private val scope: CoroutineScope,
    /** Which slice of the bus to watch; default = everything. */
    private val filter: EventFilter = EventFilter.ALL,
) {
    private val log = LoggerFactory.getLogger("scanner")

    /** Subscribe and run the fan-out loop on [scope]. Returns the [Job] so the caller owns the lifecycle. */
    fun start(): Job = scope.launch {
        log.info("scanner started with {} detector(s)", detectors.size)
        sink.subscribe(filter).collect { scan(it) }
    }

    /**
     * Fan one event out to every detector and emit any signals. A detector that throws is isolated —
     * it neither kills the loop nor blocks the other detectors (fail-soft Sense; the cost of a buggy
     * detector is a missed signal, never a stalled scanner). Exposed for direct unit testing.
     *
     * **Loop-avoidance (structural, 07 §3):** signal-emitted event types (`stall.*`/`nudge.*`/…, see
     * [SignalVocabulary]) are the loop's own output and are NOT re-dispatched to detectors. Doing it
     * once here covers *every* detector — a new detector cannot forget to ignore foreign signal types
     * and accidentally retrigger the loop. The effective wire type (`rawType ?: type.wire`) is used so
     * it holds both before and after CYP-64 enumerates these types.
     */
    suspend fun scan(e: Event) {
        if (SignalVocabulary.isSignal(e.rawType ?: e.type.wire)) return
        for (d in detectors) {
            val signal = try {
                d.onEvent(e)
            } catch (t: Throwable) {
                log.warn("detector {} threw on event {}; skipping", d::class.simpleName, e.type, t)
                null
            } ?: continue
            signals.emit(signal)
        }
    }
}
