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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-170 — the gate gap that hermetic tests + the `-p` one-shot spike missed: the REAL long-lived
 * stream-json **stdin-injection** path against a process that emits `system/init` **only after the
 * first stdin turn** (the verified `claude 2.1.196` behaviour). Driven through the REAL
 * [ProcessBuilderSpawner] (real pipes), NOT a FakeConnector and NOT `-p` one-shot.
 *
 * REPRODUCES THE HANG: with the old [com.tneff.cyppieagents.connector.ResumingSession] ready-gate
 * (sendTurn waits for bind → bind waits for the turn → the turn waits for sendTurn = deadlock), this
 * test times out. With the fix (first turn ungated), it completes.
 */
class ResumeStdinHangTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // A fake `claude`: LAZY init — emits nothing until it reads the first stdin line, then (per the real
    // CLI) `system/init` (echoing the --resume id) + an assistant + a success result. Stays long-lived.
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

    @Test
    fun resumedSession_consumesFirstInjectedTurn_overRealStdinPath() = runBlocking {
        val script = fakeClaude()
        val worktrees = Files.createTempDirectory("cyp170-wt").toFile()
        File(worktrees, "backend").mkdirs() // spawn cwd must exist
        val store = InMemorySessionStore().apply { upsert("default", "backend", "resumed-1", now = 1L) }
        val hub = Hub(HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))), InMemoryMessageStore())
        val registry = SessionRegistry()
        val connector = ClaudeCodeConnector(
            spawner = ProcessBuilderSpawner(), // REAL process + real pipes
            worktreesRoot = worktrees,
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sessionStore = store,
            projectIdOf = { "default" },
            cliCommand = script.absolutePath,
        )

        val session = connector.open("backend") // durable entry exists → the ResumingSession facade path
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        scope.launch { session.events.collect { seen.add(it) } }

        // THE REPRO: with the ready-gate, this deadlocks (the turn is never written, so init never comes,
        // so ready never opens). The fix sends the first turn ungated → init + result flow → completes.
        withTimeout(15_000) { session.sendTurn(UserTurn("hello resumed agent")) }

        // End-to-end: the resumed session bound and the turn was consumed (init + a result reached us).
        assertTrue(seen.any { it is SystemEvent }, "the resumed session must have emitted system/init (bound)")
        session.close()
    }
}
