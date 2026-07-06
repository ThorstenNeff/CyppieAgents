package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.connector.SessionEntry
import com.tneff.cyppieagents.connector.SessionStore
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-167/CYP-170 — read-before-spawn, write-after-init, and the resume facade, driven through
 * [ClaudeCodeConnector.open] with fakes that model the REAL CLI timing (CYP-170): a process emits
 * `system/init` ONLY in reaction to the first stdin turn (lazy init), never at startup. The old fakes
 * emitted init independently of any turn, which masked the deadlock — the reviewer's "fixture fidelity".
 */
class SessionResumeConnectorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // Lazy-init fake: emits NOTHING until the first `writeLine` (turn), then runs [onFirstTurn]. This is
    // the verified `claude` behaviour and the whole point of CYP-170 — a gate keyed on a pre-turn signal
    // would deadlock here, exactly as in production.
    private class FakeProc(private val onFirstTurn: (suspend (FakeProc) -> Unit)? = null) : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        val written = CopyOnWriteArrayList<String>()
        private val turns = AtomicInteger(0)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {
            written.add(line)
            if (turns.getAndIncrement() == 0) onFirstTurn?.invoke(this)
        }
        override fun destroy() { lines.close() }
    }

    private fun initLine(sid: String) = """{"type":"system","subtype":"init","session_id":"$sid"}"""
    private fun successLine(sid: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"$sid","result":"ack"}"""
    private fun errorLine(sid: String) =
        """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"$sid"}"""

    /** A process that BINDS on the first turn (init + success) — a working (resumed or fresh) session. */
    private fun boundProc(sid: String) = FakeProc { p -> p.feed(initLine(sid)); p.feed(successLine(sid)) }
    /** A STALE `--resume`: on the first turn emits an error WHILE unbound (no init), then dies. */
    private fun staleProc() = FakeProc { p -> p.feed(errorLine("stale-echo-id")); p.destroy() }

    private class RecordingSpawner(private vararg val procs: FakeProc) : ProcessSpawner {
        val commands = CopyOnWriteArrayList<List<String>>()
        private val idx = AtomicInteger(0)
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            commands.add(command)
            val i = idx.getAndIncrement()
            check(i < procs.size) { "unexpected spawn #${i + 1} (only ${procs.size} seeded) — a respawn loop?" }
            return procs[i]
        }
        fun count() = commands.size
        fun hasResume(spawnIndex: Int) = commands[spawnIndex].contains("--resume")
    }

    private class FakeSessionStore(seed: SessionEntry? = null) : SessionStore {
        val clears = AtomicInteger(0)
        val upserts = CopyOnWriteArrayList<String>()
        @Volatile private var entry: SessionEntry? = seed
        override fun find(projectId: String, agentId: String): SessionEntry? =
            entry?.takeIf { it.projectId == projectId && it.agentId == agentId }
        override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) {
            upserts.add(sessionId); entry = SessionEntry(projectId, agentId, sessionId, now, now)
        }
        override fun clear(projectId: String, agentId: String) { clears.incrementAndGet(); entry = null }
        fun sessionId() = entry?.sessionId
    }

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    private class Fixture(
        val connector: ClaudeCodeConnector,
        val hub: Hub,
        val store: FakeSessionStore,
        val spawner: RecordingSpawner,
    )

    private fun fixture(store: FakeSessionStore, projectId: String, vararg procs: FakeProc): Fixture {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val spawner = RecordingSpawner(*procs)
        val tmp = Files.createTempDirectory("resume-cwd").toFile()
        val connector = ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = { tmp },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sessionStore = store,
            projectIdOf = { projectId },
            clock = { 12345L },
        )
        return Fixture(connector, hub, store, spawner)
    }

    private fun spokeMsgs(hub: Hub) = hub.channelMessages("po", "po-backend")
    private suspend fun await(timeoutMs: Long = 5000, cond: () -> Boolean) =
        withTimeout(timeoutMs) { while (!cond()) delay(10) }

    // ---- read-before-spawn / write-after-init ----

    @Test
    fun freshStart_noResumeFlag_writesAfterInit() = runBlocking {
        val fx = fixture(FakeSessionStore(), "default", boundProc("sess-new"))
        val session = fx.connector.open("backend") // no entry → plain session, no facade
        assertFalse(fx.spawner.hasResume(0), "M1: first start has no --resume")
        assertNull(fx.store.sessionId(), "M4: nothing persisted before the first turn binds")

        withTimeout(5000) { session.sendTurn(UserTurn("hi")) } // lazy init: the turn triggers init+result
        assertEquals("sess-new", fx.store.sessionId(), "M3: the bound id is persisted write-after-init")
    }

    @Test
    fun entryPresent_prependsResumeFlag() = runBlocking {
        val fx = fixture(FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L)), "default", boundProc("sess-1"))
        val session = fx.connector.open("backend")
        withTimeout(5000) { session.sendTurn(UserTurn("hi")) }
        assertTrue(fx.spawner.hasResume(0), "M2: an existing entry yields --resume on attempt #1")
    }

    @Test
    fun differentProject_doesNotResume() = runBlocking {
        val fx = fixture(FakeSessionStore(SessionEntry("projA", "backend", "sess-A", 1L, 1L)), "projB", boundProc("x"))
        fx.connector.open("backend") // find("projB",…) == null → plain, no --resume
        await { fx.spawner.count() == 1 }
        assertFalse(fx.spawner.hasResume(0), "M10: projectId scopes the key — projB must not resume projA")
    }

    // ---- the resume facade (CYP-170 ungated-first-turn model) ----

    @Test
    fun resumeSucceeds_firstTurnBinds_noFallback_deliveredOnce() = runBlocking {
        val fx = fixture(FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L)), "default", boundProc("sess-1"))
        val session = fx.connector.open("backend")
        withTimeout(5000) { session.sendTurn(UserTurn("hi resumed")) } // binds during the turn → committed
        assertEquals(0, fx.store.clears.get(), "a successful resume never clears")
        assertEquals(1, fx.spawner.count(), "a successful resume never respawns")
        assertEquals(1, spokeMsgs(fx.hub).size, "the turn is delivered exactly once")
    }

    @Test
    fun staleResume_clearsRespawnsFresh_reinjectsTurnOnce_noDoubleDeliver() = runBlocking {
        val fx = fixture(
            FakeSessionStore(SessionEntry("default", "backend", "sess-stale", 1L, 1L)), "default",
            staleProc(), boundProc("fresh-sid"),
        )
        val session = fx.connector.open("backend")
        assertTrue(fx.spawner.hasResume(0), "attempt #1 carried --resume")

        withTimeout(8000) { session.sendTurn(UserTurn("important turn")) } // stale → fallback → re-inject

        assertEquals(1, fx.store.clears.get(), "M5: the stale entry is cleared")
        assertEquals(2, fx.spawner.count(), "exactly one respawn")
        assertFalse(fx.spawner.hasResume(1), "M6: the fresh respawn has NO --resume")
        // CYP-170 reviewer axis — the stale attempt never bound, so it never mediated: the hub sees the
        // turn EXACTLY once (from the fresh attempt), NOT once-per-attempt.
        assertEquals(1, spokeMsgs(fx.hub).size, "no double-deliver: the turn reaches the hub exactly once")
    }

    @Test
    fun staleResume_freshAlsoDies_noThirdSpawn() = runBlocking {
        val fx = fixture(
            FakeSessionStore(SessionEntry("default", "backend", "sess-stale", 1L, 1L)), "default",
            staleProc(), staleProc(), // both attempts die unbound
        )
        val session = fx.connector.open("backend")
        // The fallback fires exactly once; the re-inject on the (also-dead) fresh session surfaces as a
        // dead session — but there is NO third spawn (RecordingSpawner only seeded 2 → a 3rd would throw).
        withTimeout(8000) { session.sendTurn(UserTurn("doomed turn")) }
        delay(150)
        assertEquals(2, fx.spawner.count(), "M7: the fallback fires exactly once — no third attempt")
        assertEquals(0, spokeMsgs(fx.hub).size, "neither (unbound) attempt mediates → no spurious delivery")
    }

    @Test
    fun postBindCrash_doesNotClearOrRespawn() = runBlocking {
        val p1 = boundProc("sess-1")
        val fx = fixture(FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L)), "default", p1, staleProc())
        val session = fx.connector.open("backend")
        withTimeout(5000) { session.sendTurn(UserTurn("hi")) } // first turn binds → committed
        assertEquals(0, fx.store.clears.get(), "BOUND on the first turn → no clear")

        p1.feed(errorLine("sess-1")); p1.destroy() // a crash AFTER bind = mid-session (CYP-73), not a resume failure
        delay(200)
        assertEquals(0, fx.store.clears.get(), "R2: a post-bind crash must NOT clear the durable entry")
        assertEquals(1, fx.spawner.count(), "R2: a post-bind crash must NOT respawn")
    }

    @Test
    fun preBindClose_noSpuriousClear_noHang() = runBlocking {
        val fx = fixture(FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L)), "default", boundProc("sess-1"), staleProc())
        val session = fx.connector.open("backend")
        session.close() // close BEFORE any turn — the resume question was never asked
        delay(150)
        assertEquals(0, fx.store.clears.get(), "R3: a pre-bind close must NOT clear")
        assertEquals(1, fx.spawner.count(), "R3: a pre-bind close must NOT respawn")
        try {
            withTimeout(1000) { session.sendTurn(UserTurn("x")) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("R3: sendTurn hung after close", e)
        } catch (expected: Exception) {
            // closed session → sendTurn fails fast (no hang). Good.
        }
    }
}
