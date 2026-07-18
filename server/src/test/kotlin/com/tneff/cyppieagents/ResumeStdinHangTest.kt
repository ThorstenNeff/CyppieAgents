package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.InMemorySessionStore
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-170 — the gate gap that hermetic tests + the `-p` one-shot spike missed: the REAL long-lived stream-json
 * **stdin-injection** path against a process that emits `system/init` **only after the first stdin turn** (the verified
 * `claude 2.1.196` behaviour). Driven through the REAL [ProcessBuilderSpawner] (real pipes), NOT a FakeConnector.
 *
 * CYP-682 — the ORACLE was flaky in the merge gate. `sendTurn` returns on the SEPARATE `awaitStartupOutcome()`=BOUND
 * signal (init seen INTERNALLY), but the old assert checked `seen.any { SystemEvent }` at a FIXED POINT — and `seen` is
 * filled by a background collector through TWO async forward hops (`firstAttempt.events` → `_events` → the collector),
 * which lag `sendTurn`'s return. Under full-suite scheduling/GC jitter the forwarded init occasionally hadn't reached
 * `seen` at the fixed-point → a FALSE red. Measured: TEST-ORACLE race, NOT a prod bug — the prod path binds correctly
 * (isolated always green; `awaitStartupOutcome`=BOUND). The fix hangs the oracle on the OBSERVED init signal, not a
 * fixed-point snapshot ([gate-read-test-runtimes]: success = the event arriving, never timeout-survival).
 *
 * The three teeth pin the whole property:
 *  • [resumedSession_consumesFirstInjectedTurn_overRealStdinPath] — the FIXED end-to-end tooth: await the observed init.
 *  • [deterministicRepro_fixedPointOracleMissesLateForwardedInit] — the DETERMINISTIC repro: gate the collector (the
 *    last forward hop) → at `sendTurn`-return `seen` is empty though init IS flowing → the OLD `seen.any` fixed-point
 *    would red EVERY time; release → the observed await catches it. (No luck, no brute-force.)
 *  • [awaitedInitOracle_redsWhenInitNeverReachesSeen_nonVacuous] — the NON-VACUOSITY bar: a broken forward (the gate is
 *    never released → init never reaches `seen`) MUST red the new await. So the fix removed the noise WITHOUT castrating
 *    the tooth — it still pins "init actually flows", not merely "the test survives".
 */
class ResumeStdinHangTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // A fake `claude`: LAZY init — emits nothing until it reads the first stdin line, then (per the real CLI)
    // `system/init` (echoing the --resume id) + an assistant + a success result. Stays long-lived.
    private val FAKE_CLAUDE = """
        #!/usr/bin/env bash
        sid="fresh-sid"
        prev=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--resume" ]; then sid="${'$'}a"; fi
          prev="${'$'}a"
        done
        first=1
        while IFS= read -r _line; do
          if [ ${'$'}first -eq 1 ]; then
            printf '%s\n' "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"${'$'}sid\"}"
            first=0
          fi
          printf '%s\n' "{\"type\":\"assistant\",\"session_id\":\"${'$'}sid\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ack\"}]}}"
          printf '%s\n' "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"${'$'}sid\",\"result\":\"ack\"}"
        done
    """.trimIndent()

    private fun fakeClaude(): File {
        val f = Files.createTempFile("fake-claude", ".sh").toFile()
        f.writeText(FAKE_CLAUDE)
        f.setExecutable(true)
        return f
    }

    /** Open a resumed session over the REAL stdin path (durable entry exists → the ResumingSession facade). Return type
     *  inferred so the exact session type never has to be named. */
    private fun openResumedSession() = run {
        val script = fakeClaude()
        val worktrees = Files.createTempDirectory("cyp170-wt").toFile()
        File(worktrees, "backend").mkdirs() // spawn cwd must exist
        val store = InMemorySessionStore().apply { upsert("default", "backend", "resumed-1", now = 1L) }
        val hub = Hub(HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))), InMemoryMessageStore())
        val registry = SessionRegistry()
        val connector = ClaudeCodeConnector(
            spawner = ProcessBuilderSpawner(), // REAL process + real pipes
            worktreesRoot = { worktrees },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sessionStore = store,
            cliCommand = script.absolutePath,
        )
        // CYP-247 S1b: projectId threaded via open(); "default" == DEFAULT_PROJECT_ID keys the durable entry.
        connector.open("backend", "backend", "default")
    }

    @Test
    fun resumedSession_consumesFirstInjectedTurn_overRealStdinPath() = runBlocking {
        val session = openResumedSession()
        // ★ CYP-682 fix: complete a deferred WHEN the forwarded init actually reaches the collector — the OBSERVED
        //   signal, not a fixed-point snapshot of `seen`.
        val initSeen = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        scope.launch { session.events.collect { seen.add(it); if (it is SystemEvent) initSeen.complete(Unit) } }

        // Ungated first turn: the CLI emits system/init only while processing a turn (CYP-170), so the turn IS the
        // probe. With the old ready-gate this deadlocked; ungated it binds + the events flow.
        withTimeout(15_000) { session.sendTurn(UserTurn("hello resumed agent")) }

        // ★ AWAIT the observed init — green iff the forwarded system/init reaches the collector (fast, deterministic);
        //   a timeout here means init NEVER flowed = a real bind failure, not a scheduling snapshot.
        withTimeout(15_000) { initSeen.await() }
        assertTrue(seen.any { it is SystemEvent }, "the resumed session emitted system/init (bound) and it reached the collector")
        session.close()
    }

    @Test
    fun deterministicRepro_fixedPointOracleMissesLateForwardedInit() = runBlocking {
        // DETERMINISTIC repro of the CYP-682 race (no brute-force, no load): gate the collector — the LAST forward hop —
        // so it receives the forwarded events but holds before appending to `seen`. Then at `sendTurn`-return the
        // session is BOUND (init flowed internally) yet `seen` is empty → the OLD fixed-point `seen.any { SystemEvent }`
        // would be a FALSE red EVERY time. Releasing the gate delivers the SAME init → proving it was flowing, the old
        // oracle just checked too early.
        val session = openResumedSession()
        val gate = CompletableDeferred<Unit>()
        val initSeen = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        scope.launch { session.events.collect { gate.await(); seen.add(it); if (it is SystemEvent) initSeen.complete(Unit) } }

        withTimeout(15_000) { session.sendTurn(UserTurn("hello resumed agent")) } // binds (awaitStartupOutcome=BOUND)

        // ★ THE RACE, deterministic: bound, but the gated collector has forwarded nothing to `seen` → the OLD oracle reds.
        assertTrue(
            seen.none { it is StreamJsonEvent },
            "DETERMINISTIC repro: sendTurn returned BOUND but the forwarded init has NOT reached `seen` (collector gated) " +
                "→ the OLD fixed-point `seen.any { SystemEvent }` would be a false red here. seen=$seen",
        )

        // Release the gate → the SAME init flows → the observed await catches it (the fix's oracle is correct).
        gate.complete(Unit)
        withTimeout(15_000) { initSeen.await() }
        assertTrue(seen.any { it is SystemEvent }, "after releasing the gate the init IS in `seen` — it was flowing all along, just later than the fixed point")
        session.close()
    }

    @Test
    fun awaitedInitOracle_redsWhenInitNeverReachesSeen_nonVacuous() = runBlocking {
        // NON-VACUOSITY (CYP-682 point 4): break the forward — the gate is NEVER released, so the forwarded init can
        // never reach `seen`/the signal. The new awaited oracle MUST red (time out). This proves the fix still pins
        // "init actually flows to the collector", not merely "the test survives" — it did not deflake to vacuous.
        val session = openResumedSession()
        val brokenForward = CompletableDeferred<Unit>() // never completed → init is held forever before `seen`
        val initSeen = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        scope.launch { session.events.collect { brokenForward.await(); seen.add(it); if (it is SystemEvent) initSeen.complete(Unit) } }

        withTimeout(15_000) { session.sendTurn(UserTurn("hello resumed agent")) } // binds — init flows internally...

        // ...but the forward to `seen` is broken → the observed await times out → RED (as it must). A bounded window
        // stands in for the real (generous) deadline; `null` == the await did NOT survive-on-timeout, it reddened.
        val arrived = withTimeoutOrNull(2_000) { initSeen.await() }
        assertNull(
            arrived,
            "the awaited init oracle REDS (times out) when the forward to `seen` is broken — it pins that init ACTUALLY " +
                "reaches the collector, so the deflake did not castrate the tooth (non-vacuous).",
        )
        session.close()
    }
}
