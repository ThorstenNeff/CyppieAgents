package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.scanner.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The **Decide+Act** stage (07 §2/§3). The Warden is a *separate* bus consumer from the Scanner: the
 * Scanner's loop-avoidance filter keeps signal Events away from the **detectors**, but the Warden is a
 * different consumer that listens precisely for them — it `subscribe`s to the same [EventSink], turns
 * each signal Event back into a [Signal] ([Signal.fromEvent]), and routes it to the [Policy] that
 * [handles][Policy.handles] its type.
 *
 * The Warden carries no agent handle; the only thing it can do to an agent is hand the [actuator] to a
 * policy. This is the scaffold (CYP-62): the frame plus an empty policy set. The stall policy (CYP-63)
 * registers here unchanged.
 */
class Warden(
    private val sink: EventSink,
    private val policies: List<Policy>,
    private val actuator: Actuator,
    private val scope: CoroutineScope,
    private val filter: EventFilter = EventFilter.ALL,
) {
    private val log = LoggerFactory.getLogger("warden")

    fun start(): Job = scope.launch {
        log.info("warden started with {} policy(ies)", policies.size)
        sink.subscribe(filter).collect { dispatch(it) }
    }

    /**
     * Route one event: ignore non-signal events, otherwise hand the reconstructed [Signal] to every
     * policy that handles its type. A policy that throws is isolated — it neither kills the loop nor
     * blocks the other policies (a buggy policy must not silence the whole supervisor). Exposed for
     * direct unit testing.
     */
    suspend fun dispatch(e: Event) {
        val signal = Signal.fromEvent(e) ?: return
        for (p in policies) {
            if (!p.handles(signal.type)) continue
            try {
                p.onSignal(signal, actuator)
            } catch (t: Throwable) {
                log.warn("policy {} threw on signal {}; skipping", p::class.simpleName, signal.type, t)
            }
        }
    }
}
