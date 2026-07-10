package com.tneff.cyppieagents.boot

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-368 — **the agent's transition lock. It belongs to the transition, not to a manager.**
 *
 * An agent's lifecycle transition is one atomic act that spans more than one component: the mediated session
 * ([LifecycleManager]) and, once BE-2 lands, the interactive PTY (`PtyManager`). Under two separate locks there
 * is a window in which the mediated session and the PTY are both live — the two-process violation this ticket
 * exists to close — plus a lock-ordering hazard between them.
 *
 * Exposing an `internal val mutex` from one manager and letting the other grab it would not be a shared lock; it
 * would be a leak. The order would be unobservable and the owner undefined. **If the transition belongs to both,
 * the lock belongs to neither** — so it is its own object, constructed by the wiring (one per runtime, since an
 * `agentId` is only unique within a project) and handed to every manager that participates.
 *
 * ## The rule, and it grows with every participant
 *
 * The lock is held across teardown that **waits**: `LifecycleManager.stop`/`restart` →
 * `ConnectorSessions.removeAndAwait` → `closeAndAwait()` → `process.destroy()` → `readerJob.join()`.
 *
 * Since CYP-371 that join is a real wait on a coroutine that **runs to completion** — the reader is flushed, not
 * cancelled. So the session's exit tail (the observer callback and the exit listeners it fires) executes *inside
 * the interval a lock-holder is blocked on*. Before CYP-371 the tail was cancelled and could not run at all,
 * which made this rule theoretical. It is now load-bearing:
 *
 * > **Nothing that a lock-holder waits for may itself take this lock.**
 *
 * Concretely, today:
 *  - the exit listeners on this base are owned by CYP-360's `ResumingSession`, which takes no lock. **CYP-351
 *    adds `LifecycleManager.onObservedExit`** as a further consumer of that tail — it must take the manager's own
 *    `synchronized` monitor, never this lock, and everything it invokes (`onBusyReset`/`onContextReset` → the
 *    busy/token trackers) must not take it either. Checked here so the CYP-351 stack lands onto a stated rule.
 *  - `PtyHandle.close()` currently does **not** wait: it `destroy()`s and returns, and the pump coroutine ends
 *    on its own and fires `onExit(code)`. So it adds no second way into the same hang **today**. A graceful
 *    stop that *awaits* the pump or `onExit` would — and then `onExit` must not take this lock either.
 *
 * Deadlocking here is not sporadic: it happens on the first `stop()` with a live tail, every time.
 *
 * ## What it costs
 *
 * The lock is held across a `fork/exec` (and, once BE-2 lands, a PTY open and a graceful stop). A `POST /stop`
 * that arrives during a spawn **waits** for it. That is the intended semantics: stopping a half-spawned agent is
 * meaningless, and it is exactly how an orphan session is produced.
 *
 * Measured on the build host, so the product decision rests on a number rather than on "bounded":
 *
 * | held across                       | min | median | p95   |
 * |-----------------------------------|-----|--------|-------|
 * | `fork/exec` (ProcessBuilder)      | 1.4 | 1.6    | 31.5  |
 * | PTY open (pty4j, for BE-2)        | 8.8 | 12.2   | 104.6 |
 *
 * (milliseconds; n=20 / n=10). The p95 outliers are first-call JIT/loader effects, not steady state. A graceful
 * stop is BE-2's to measure — a hand-off holds the lock for the sum, and that sum is the real product decision.
 */
class AgentTransitionLock {

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(agentId: String): Mutex = mutexes.computeIfAbsent(agentId) { Mutex() }

    /** Run [block] as the agent's only transition. Suspends while another transition for the SAME agent runs. */
    suspend fun <T> withAgent(agentId: String, block: suspend () -> T): T = mutexFor(agentId).withLock { block() }

    /**
     * Blocking variant for the boot path, which is not `suspend` and runs on the startup thread before any
     * request is served — nothing to contend with, nothing to block. It exists so that **every** writer takes
     * the lock: a rule with an exception is a rule someone has to remember.
     */
    fun <T> withAgentBlocking(agentId: String, block: () -> T): T = runBlocking {
        mutexFor(agentId).withLock { block() }
    }
}
