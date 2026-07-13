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
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-330 (Bug, High) — a RESTART with a STALE `--resume` must self-heal WITHOUT a turn. The stale-resume
 * heal previously lived ONLY in [com.tneff.cyppieagents.connector.ResumingSession.sendTurn] (the first-turn
 * probe), but a restart sends no turn → the dead/locked resume was never detected → the agent hung dead and
 * the operator had to press START manually. The fix adds a PROACTIVE `awaitStartupOutcome()` probe at
 * `start()`: a stale `--resume` dies unbound at startup (error result / stdout ends), so the probe clears the
 * durable entry + respawns fresh (context-free) with NO turn — while a LIVE resume stays pending and keeps
 * its context (binds on the first real turn, CYP-170).
 *
 * Driven through the REAL [ProcessBuilderSpawner] (real pipes) with a fake `claude`, like [ResumeStdinHangTest].
 */
class Cyp330RestartRobustnessTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // A fake `claude`: a STALE resume id ("STALE") fails fast at startup — an error result while UNBOUND,
    // then exit, WITHOUT reading stdin (models the real dead `--resume` dying before any turn). Any other
    // launch (fresh = no --resume, or a live resume id) lazily binds on the first stdin turn (CYP-170).
    private val FAKE_CLAUDE = """
        #!/usr/bin/env bash
        sid="fresh-sid"
        resume=""
        prev=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--resume" ]; then resume="${'$'}a"; sid="${'$'}a"; fi
          prev="${'$'}a"
        done
        if [ "${'$'}resume" = "STALE" ]; then
          printf '%s\n' "{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true,\"result\":\"no conversation found to resume\"}"
          exit 1
        fi
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
        val f = Files.createTempFile("fake-claude-330", ".sh").toFile()
        f.writeText(FAKE_CLAUDE)
        f.setExecutable(true)
        return f
    }

    private fun connector(script: File, store: InMemorySessionStore, worktrees: File): ClaudeCodeConnector {
        val hub = Hub(
            HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))),
            InMemoryMessageStore(),
        )
        val registry = SessionRegistry()
        return ClaudeCodeConnector(
            spawner = ProcessBuilderSpawner(),
            worktreesRoot = { worktrees },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sessionStore = store,
            cliCommand = script.absolutePath,
        )
    }

    /**
     * CYP-531 — a session store that SIGNALS its first [clear] via a [CompletableDeferred]. The proactive heal
     * clears the durable entry through `ClaudeCodeConnector`'s `onResumeFailed = { sessionStore.clear(..) }`, so the
     * test awaits THAT signal deterministically instead of a `delay(50)`-poll time window (the last-flake source).
     */
    private class SignallingSessionStore : InMemorySessionStore() {
        val cleared = CompletableDeferred<Unit>()
        override fun clear(projectId: String, agentId: String) {
            super.clear(projectId, agentId)
            cleared.complete(Unit) // idempotent — complete() no-ops if already completed
        }
    }

    @Test
    fun restartWithStaleResume_healsToFreshWithoutATurn() = runBlocking {
        val script = fakeClaude()
        val worktrees = Files.createTempDirectory("cyp330-wt").toFile()
        File(worktrees, "backend").mkdirs()
        val store = SignallingSessionStore().apply { upsert("default", "backend", "STALE", now = 1L) }
        val connector = connector(script, store, worktrees)

        // The restart scenario: open the resume-facade session and start it, but send NO turn. Signal the first
        // SystemEvent binding through a deferred (not an async-collected list checked after sendTurn — the race).
        val session = connector.open("backend", "backend", "default")
        val systemBound = CompletableDeferred<Unit>()
        scope.launch { session.events.collect { if (it is SystemEvent) systemBound.complete(Unit) } }

        // The PROACTIVE probe must detect the stale resume died unbound and clear the durable entry — with NO turn.
        // Deterministic: await the actual clear() signal (fired by onResumeFailed), NOT a timing poll. Before the
        // CYP-330 fix (turn-only heal) the entry is never cleared without a turn → this await times out → RED.
        withTimeout(15_000) { store.cleared.await() }
        assertNull(store.find("default", "backend"), "the stale durable entry is cleared proactively (no turn)")

        // And the healed session is a live FRESH one: the next turn binds it (agent alive, not dead-in-ERROR).
        // Deterministic: await the SystemEvent binding on the events flow — no window race on a collected list.
        withTimeout(15_000) {
            session.sendTurn(UserTurn("hello after restart"))
            systemBound.await()
        }
        assertTrue(systemBound.isCompleted, "the fresh session bound on the first real turn (recovered)")
        session.close()
    }

    @Test
    fun restartWithLiveResume_preservesContext_noClear() = runBlocking {
        // A LIVE resume (id != STALE) must NOT be cleared by the proactive probe — the CLI stays alive waiting
        // for stdin (CYP-170), so awaitStartupOutcome stays pending and the durable entry survives. This is the
        // "KEIN pauschales sessionStore.clear bei jedem Restart" guardrail — context is preserved.
        val script = fakeClaude()
        val worktrees = Files.createTempDirectory("cyp330-live-wt").toFile()
        File(worktrees, "backend").mkdirs()
        val store = InMemorySessionStore().apply { upsert("default", "backend", "live-1", now = 1L) }
        val connector = connector(script, store, worktrees)

        val session = connector.open("backend", "backend", "default")
        scope.launch { session.events.collect { } }

        // Give the proactive probe ample time to (wrongly) fire; a live resume must survive it untouched.
        delay(1_500)
        val entry = store.find("default", "backend")
        assertNotNull(entry, "a LIVE resume entry must NOT be cleared proactively (context preserved)")
        assertEquals("live-1", entry.sessionId, "the live resume id is untouched — no blanket clear")
        session.close()
    }
}
