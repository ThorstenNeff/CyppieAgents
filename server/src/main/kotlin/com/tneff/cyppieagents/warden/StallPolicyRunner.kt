package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.scanner.StallDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Drives the [StallPolicy]'s two time-/stream-based inputs (07 §5.2), the analogue of the Scanner's
 * `StallSweeper`:
 *  - it `subscribe`s to the bus and feeds **agent activity** (turn/tool/token/result — the same set
 *    the detector disarms on) into [StallPolicy.onActivity], so recovery is detected;
 *  - it [tick][StallPolicy.tick]s the policy every [tickMs] so backoff/escalation advance.
 *
 * The runner holds no agent handle and emits nothing itself — all effects go through the policy →
 * Actuator. `onSignal` (incident opening) is delivered separately by the Warden; the runner only
 * supplies activity + the clock tick, so there is no double-handling of `stall.suspected`.
 */
class StallPolicyRunner(
    private val policy: StallPolicy,
    private val sink: EventSink,
    private val scope: CoroutineScope,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val filter: EventFilter = EventFilter.ALL,
) {
    private val log = LoggerFactory.getLogger("warden.stall-runner")

    /** Activity feed: forward agent-progress events to the policy for recovery detection. */
    fun startActivityFeed(): Job = scope.launch {
        sink.subscribe(filter).collect { e ->
            if (e.type in StallDetector.ACTIVITY_TYPES) policy.onActivity(e.agentId)
        }
    }

    /** Backoff clock: tick the policy so due nudges/escalations fire. */
    fun startTicker(): Job = scope.launch {
        log.info("stall policy ticker started (tick={}ms)", tickMs)
        while (isActive) {
            delay(tickMs)
            policy.tick()
        }
    }

    fun start() {
        startActivityFeed()
        startTicker()
    }

    companion object {
        const val DEFAULT_TICK_MS = 10_000L
    }
}
