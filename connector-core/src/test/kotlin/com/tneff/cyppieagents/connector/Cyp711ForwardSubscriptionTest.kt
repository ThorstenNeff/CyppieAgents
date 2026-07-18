package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-711 — `ResumingSession.forward` must be a REGISTERED subscriber before it returns.
 *
 * ### The property
 * Both call sites are `forward(session)` immediately followed by `session.start()`
 * ([ResumingSession.start] :139-141, `healToFreshLocked` :203-205). A plain `launch` hands back a `Job`, which
 * is **not a subscription receipt** — the coroutine may not have reached `collect` when the reader starts
 * emitting. `ClaudeCodeSession._events` is **replay=0**, so an emission with no subscriber is *dropped*, not
 * queued: the event never reaches the facade and no downstream collector can recover it. Every consumer of a
 * fresh session could therefore lose its first event.
 *
 * ### Why these tests are shaped the way they are
 * The window is a couple of coroutine dispatches wide and **cannot be reproduced by load** — measured: 0 losses
 * in 300 iterations at 32 load threads on 12 cores, matching the CYP-341 ticket's own 4/4-green-under-load note
 * from 2026-07-10. So the property is pinned by **ordering**, deterministically, not by hammering.
 *
 * ### What each test pins
 *  ① `undispatchedLaunch_isSubscribedBeforeItReturns` — **the assurance the production fix rests on.**
 *    `CoroutineStart.UNDISPATCHED` + `SharedFlow.collect` registering its slot before it suspends is
 *    *implementation behaviour*, not a documented guarantee. This asserts it directly, so if the runtime ever
 *    stops honouring it the guard reds here instead of silently evaporating in production.
 *  ② `plainLaunch_isNotYetSubscribed_soAnImmediateEmitIsLost` — the negative control that makes ① meaningful:
 *    the same construction with a plain `launch` deterministically LOSES the emission. Without ②, ① could pass
 *    on a harness that always delivers.
 *  ③ `replayOne_doesNotCoverABurst` — pins why the alternative was rejected. `replay = 1` hands a late
 *    subscriber only the LAST of a burst (`system/init`, `assistant`, `result`), so the first event is still
 *    lost. Measured RED as CYP-341 arm C; kept here as a regression guard so nobody "simplifies" the fix into
 *    a replay that cannot cover an unbounded burst.
 *
 * ### Boundary
 * ①-③ pin the ordering guarantee of the idiom, on the same flow type and replay semantics as production — but
 * they run their OWN `launch`, so on their own they say nothing about whether production still uses that form.
 * Verified, not assumed: with `UNDISPATCHED` deleted from [ResumingSession.forward] all three stay GREEN. That
 * is why ④ exists.
 *
 * A behavioural test cannot close this honestly. `ClaudeCodeSession.start` launches its stdout reader with a
 * plain (dispatched) `launch`, so an emission can never be made to land synchronously inside the forwarder's
 * window — which is exactly why the race is unreproducible by load (0/300). Forcing it would take a production
 * seam that exists only for the test. So ④ pins the call site by SOURCE instead: cruder, but it actually reds
 * when the guarantee is removed, which the behavioural tests do not.
 */
class Cyp711ForwardSubscriptionTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private fun sysEvent(id: String) = SystemEvent(sessionId = id, subtype = "init")

    /** Mirrors production: replay=0, extraBufferCapacity=256 (ClaudeCodeSession.kt:53, ResumingSession.kt:59). */
    private fun upstream() = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)

    // ---------- ① the assurance the fix rests on ----------
    @Test
    fun undispatchedLaunch_isSubscribedBeforeItReturns() = runBlocking {
        val flow = upstream()
        val seen = CompletableDeferred<StreamJsonEvent>()

        // Exactly the shape of ResumingSession.forward(...).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { seen.complete(it) } }

        // Emit with NO suspension point in between — i.e. `start()` running straight after `forward()`.
        // tryEmit returning true on a replay=0 flow means it went to a live subscriber (or was buffered for
        // one); with zero subscribers the value would simply be dropped and never observed below.
        assertTrue(flow.tryEmit(sysEvent("fresh-sid")), "the emission was accepted")

        val got = withTimeoutOrNull(5_000) { seen.await() }
        assertTrue(got != null, "CYP-711: the forwarder must already be subscribed when forward() returns, so an emission that lands immediately after is delivered")
        assertEquals("fresh-sid", (got as SystemEvent).sessionId)
    }

    // ---------- ② the negative control — plain launch deterministically loses it ----------
    @Test
    fun plainLaunch_isNotYetSubscribed_soAnImmediateEmitIsLost() = runBlocking {
        val flow = upstream()
        val seen = CompletableDeferred<StreamJsonEvent>()

        // The pre-CYP-711 form: a dispatched launch has NOT run its body yet when launch() returns.
        scope.launch { flow.collect { seen.complete(it) } }

        flow.tryEmit(sysEvent("fresh-sid"))

        val got = withTimeoutOrNull(1_000) { seen.await() }
        assertTrue(
            got == null,
            "the negative control must LOSE the event — if this ever delivers, the ordering hazard has changed " +
                "and ①'s guarantee is no longer what makes the difference",
        )
    }

    // ---------- ③ why replay=1 was rejected: it cannot cover a burst ----------
    @Test
    fun replayOne_doesNotCoverABurst() = runBlocking {
        // A turn emits system/init, assistant, result back-to-back. replay=1 keeps only the LAST.
        val flow = MutableSharedFlow<StreamJsonEvent>(replay = 1, extraBufferCapacity = 256)
        flow.tryEmit(sysEvent("first"))
        flow.tryEmit(sysEvent("second"))
        flow.tryEmit(sysEvent("third"))

        val replayed = flow.replayCache.map { (it as SystemEvent).sessionId }
        assertEquals(listOf("third"), replayed, "replay=1 retains only the last of a burst")
        assertFalse(
            "first" in replayed,
            "CYP-711/CYP-341 arm C: a late subscriber never sees the FIRST event of a burst under replay=1 — " +
                "which is why the fix is subscribe-before-emit (burst-independent), not a replay window",
        )
    }

    // ---------- ④ the call site actually uses the guaranteed form ----------
    /**
     * ①-③ prove what `UNDISPATCHED` guarantees; this proves production still ASKS for it. Without ④ the whole
     * class is a vacuous guard: deleting `UNDISPATCHED` from [ResumingSession.forward] leaves ①-③ green
     * (measured), so the fix could be reverted with every test passing.
     *
     * A source assertion is the crude instrument, chosen deliberately over a behavioural one that cannot be made
     * deterministic (see the class KDoc). It reds on exactly the edit that reopens the hole.
     */
    @Test
    fun forwardCallSite_usesUndispatchedStart() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/com/tneff/cyppieagents/connector/ResumingSession.kt"),
            java.io.File("connector-core/src/main/kotlin/com/tneff/cyppieagents/connector/ResumingSession.kt"),
        )
        val src = candidates.firstOrNull { it.isFile }
        assertTrue(src != null, "ResumingSession.kt not found from ${java.io.File(".").absolutePath} — tried ${candidates.map { it.path }}")
        val text = src.readText()

        val idx = text.indexOf("private fun forward(")
        assertTrue(idx >= 0, "the `forward` declaration moved or was renamed — re-point this guard at its new form")
        val body = text.substring(idx, minOf(text.length, idx + 400))

        assertTrue(
            "CoroutineStart.UNDISPATCHED" in body,
            "CYP-711: `ResumingSession.forward` must launch with CoroutineStart.UNDISPATCHED so the forwarder is a " +
                "registered subscriber before `session.start()` can emit into a replay=0 flow. Found instead:\n" +
                body.lineSequence().take(4).joinToString("\n"),
        )
    }
}
