package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.InMemorySessionStore
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ResumeOutcome
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.events.SystemTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-356 (BE-3) — the `resume.outcome` Event-Log surface, driven through the REAL [ResumingSession] FACADE
 * (real [ProcessBuilderSpawner] + a fake `claude`, like [Cyp330RestartRobustnessTest]) — NOT [ClaudeCodeSession]
 * directly (the fixture-fidelity lesson from the CYP-360 addExitListener no-op). Surfaces the EXISTING CYP-330
 * detection: `--resume` binds → RESUMED_WITH_CONTEXT; dies unbound → heals fresh → CONTEXT_LOST; no durable
 * id → FRESH_NO_RESUME. Content-free (detail = `{outcome}` only). Emitted via [EventRecorder] directly.
 */
class Cyp356ResumeOutcomeTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // A stale resume id ("STALE") fails fast at startup (error while unbound → exit). Any other launch (fresh
    // or a live resume id) binds on the first stdin turn (CYP-170).
    private val FAKE_CLAUDE = """
        #!/usr/bin/env bash
        sid="fresh-sid"; resume=""; prev=""
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
          if [ ${'$'}first -eq 1 ]; then printf '%s\n' "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"${'$'}sid\"}"; first=0; fi
          printf '%s\n' "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"${'$'}sid\",\"result\":\"ack\"}"
        done
    """.trimIndent()

    private fun fakeClaude(): File =
        Files.createTempFile("fake-claude-356", ".sh").toFile().apply { writeText(FAKE_CLAUDE); setExecutable(true) }

    private class Harness(val scope: CoroutineScope) {
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        fun connector(script: File, store: InMemorySessionStore, worktrees: File): ClaudeCodeConnector {
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
                recorder = recorder,
            )
        }
    }

    private fun worktrees(name: String): File =
        Files.createTempDirectory(name).toFile().also { File(it, "backend").mkdirs() }

    private suspend fun InMemoryEventSink.awaitResumeOutcome(): Event = withTimeout(15_000) {
        while (true) {
            val e = query(EventFilter(type = EventType.RESUME_OUTCOME), Page()).events
            if (e.isNotEmpty()) return@withTimeout e.first()
            delay(30)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun Event.outcome(): String = (detail["outcome"] as JsonPrimitive).content

    @Test
    fun staleResume_healsFresh_emitsContextLost_contentFree() = runBlocking {
        val h = Harness(scope)
        val store = InMemorySessionStore().apply { upsert("default", "backend", "STALE", now = 1L) }
        h.connector(fakeClaude(), store, worktrees("cyp356-lost")).open("backend", "backend", "default")
        // The proactive CYP-330 probe heals the stale --resume WITHOUT a turn → intrinsic CONTEXT_LOST.
        val ev = h.sink.awaitResumeOutcome()
        assertEquals(ResumeOutcome.CONTEXT_LOST.name, ev.outcome(), "a stale --resume that heals to fresh is CONTEXT_LOST")
        assertEquals(Severity.WARN, ev.severity, "CONTEXT_LOST is a WARN")
        assertEquals(setOf("outcome"), ev.detail.keys, "content-free: detail carries only {outcome} (no conversation content)")
        assertEquals("STALE", ev.sessionId, "the resumed sid rides the standard sessionId field")
    }

    @Test
    fun liveResume_binds_emitsResumedWithContext() = runBlocking {
        val h = Harness(scope)
        val store = InMemorySessionStore().apply { upsert("default", "backend", "live-1", now = 1L) }
        val session = h.connector(fakeClaude(), store, worktrees("cyp356-ok")).open("backend", "backend", "default")
        // A live --resume binds on the first real turn (CYP-170) → intrinsic RESUMED_WITH_CONTEXT.
        withTimeout(15_000) { session.sendTurn(UserTurn("hello")) }
        val ev = h.sink.awaitResumeOutcome()
        assertEquals(ResumeOutcome.RESUMED_WITH_CONTEXT.name, ev.outcome(), "a --resume that binds kept its context")
        assertEquals(Severity.INFO, ev.severity, "a successful resume is INFO")
    }

    @Test
    fun noDurableId_emitsFreshNoResume() = runBlocking {
        val h = Harness(scope)
        val store = InMemorySessionStore() // empty → no --resume, no facade
        h.connector(fakeClaude(), store, worktrees("cyp356-fresh")).open("backend", "backend", "default")
        val ev = h.sink.awaitResumeOutcome()
        assertEquals(ResumeOutcome.FRESH_NO_RESUME.name, ev.outcome(), "no durable id → fresh by design, NOT a loss")
        assertTrue(ev.sessionId == null, "FRESH_NO_RESUME carries no sid")
    }
}
