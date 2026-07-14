package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.controlplane.AdmissionRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CYP-536 (M2 Option A, WS1) — the source of the session's **rendezvous-id SET**. Per the ratified C4 (develop
 * `889e6919`): the CP mints a 128-bit secret epoch (never on the wire) and derives `id_i = base64url(SHA-256(hubId ‖
 * epoch ‖ i))` for `i ∈ 0..poolCap-1`; register/resolve return the SET. This seam yields that set on the hub side (the
 * epoch-N-set register is wired behind it). `null`/empty ⇒ the set is **not yet available** — a transient CP failure,
 * a boot-admission race, or a not-yet-owned hub — which the manager **retries** (CYP-553), never a one-shot give-up.
 * Kept a seam so the manager unit-tests without a real CP/relay.
 */
fun interface SessionRendezvousSource {
    suspend fun rendezvousIds(): List<String>?
}

/**
 * CYP-536 (M2 Option A, WS1) — the **N-concurrent responder manager**. It fans the single serial [NoiseRelayConnector]
 * (dial ONE cached id → NK-terminate → RR3-gate → bridge ONE tunnel → re-dial) into **N concurrent responders**, one
 * per rendezvous-id in the session's epoch-derived set ([source]). Each responder is an independent, persistent
 * [RelayConnector] built by [responderFor] for a FIXED id, so the hub is present at the relay on **all N ids at once**
 * — N client tunnels pair concurrently instead of serializing behind one (the fix for F-M2-1).
 *
 * **Server-side per-operator tunnel CAP (WS6 C5 axis 2, the DoS floor).** The manager launches at most [poolCap]
 * responders — and the CP derives exactly [poolCap] rendezvous-ids per session — so ≤ [poolCap] distinct tunnels can
 * pair for one operator. The `.take(poolCap)` is the belt-and-suspenders guard should the source ever over-yield.
 *
 * **CYP-553 — the set-fetch is RETRIED, not one-shot.** The manager is only constructed when the relay is configured
 * (`buildRemoteTransport` gates it, else `InertRelayConnector`), so a `null`/empty [source] is a **transient** failure
 * — a CP hiccup / boot-admission race in the ~8s boot window, or a not-yet-owned hub — **not** a legitimate INERT. The
 * old one-shot logged INFO-INERT and returned → **zero responders forever, hub silently dark on the relay until a
 * manual restart** (a regression of the CYP-526 one-shot class: the single-connector re-registered each dial inside its
 * reconnect loop). Now [start] launches a **supervisory retry loop** on [scope] that re-fetches the set with backoff
 * until it resolves, then fans out and exits (each per-id responder then owns its own persistent reconnect loop).
 * Idempotent + thread-safe. Fail-closed throughout: a single responder's failure is contained to its own loop.
 */
class ConcurrentRelayResponderManager(
    private val source: SessionRendezvousSource,
    private val responderFor: (rendezvousId: String) -> RelayConnector,
    /** The server-side per-operator tunnel cap = the epoch-set size the CP derives (single-sourced with the CP). */
    private val poolCap: Int,
    private val scope: CoroutineScope,
    /** CYP-553 — backoff between rendezvous-set-fetch retries. Reuses the CYP-524 [AdmissionRetry] exponential curve
     *  (capped). Injectable for tests (fast/deterministic). */
    private val backoffMs: (attempt: Int) -> Long = { a -> AdmissionRetry().delayForAttempt(a) },
    /** Injectable sleeper so tests drive the retry cadence without real waiting; prod = [delay]. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : RelayConnector {
    private val log = LoggerFactory.getLogger("cyp536.responder.manager")
    private val responders = CopyOnWriteArrayList<RelayConnector>() // CYP-553 (⑦): thread-safe
    private val started = AtomicBoolean(false)                       // CYP-553 (⑦): idempotent start (never a 2nd fan-out)
    private var supervisor: Job? = null

    override suspend fun start() {
        if (!started.compareAndSet(false, true)) return // a 2nd start() is a no-op — never launches a second N responders
        supervisor = scope.launch { resolveAndFanOut() }
    }

    /** Retry the set-fetch until it resolves, then fan out one responder per id and exit (the per-id responders
     *  self-heal from there). A transient CP failure / boot-admission race no longer leaves the hub dark (CYP-553). */
    private suspend fun CoroutineScope.resolveAndFanOut() {
        var attempt = 0
        while (isActive) {
            val ids = try {
                source.rendezvousIds()
            } catch (c: CancellationException) {
                throw c // stop() cancelled us — never swallow cancellation
            } catch (e: Exception) {
                log.warn("CYP-553: rendezvous-set fetch threw (attempt {}) — retrying: {}", attempt + 1, e.message)
                null
            }
            if (!ids.isNullOrEmpty()) {
                val capped = ids.take(poolCap) // WS6 axis 2 — never exceed the per-operator cap even on over-yield
                if (ids.size > poolCap) {
                    log.warn("CYP-536 rendezvous set size {} > poolCap {} — capping to the per-operator tunnel cap", ids.size, poolCap)
                }
                for (id in capped) {
                    val responder = responderFor(id)
                    responders += responder
                    responder.start() // NoiseRelayConnector.start() launches its own persistent loop → non-blocking
                }
                log.info("CYP-536 N-responder manager started {} concurrent responders (cap {})", capped.size, poolCap)
                return // resolved — the per-id responders own their reconnect loops; the supervisor's job is done
            }
            attempt++
            log.warn(
                "CYP-553: rendezvous set not yet available (attempt {}) — retrying (transient CP / boot-admission race / " +
                    "hub not yet owned). NOT dark: the manager keeps trying instead of a silent one-shot INERT.", attempt,
            )
            sleep(backoffMs(attempt))
        }
    }

    override suspend fun stop() {
        supervisor?.cancelAndJoin() // stop retrying / abandon a pending fetch
        supervisor = null
        responders.forEach { runCatching { it.stop() } } // best-effort teardown of every per-id responder
        responders.clear()
    }
}
