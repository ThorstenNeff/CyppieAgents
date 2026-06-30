package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ConnectorSession
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
 * CYP-167 — read-before-spawn, write-after-init, and the [com.tneff.cyppieagents.connector.ResumingSession]
 * stale-fallback, driven end-to-end through [ClaudeCodeConnector.open] with fake processes whose startup
 * outcome the test controls (bind via system/init, or die-unbound via an error result / closed stdout).
 */
class SessionResumeConnectorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        val written = CopyOnWriteArrayList<String>()
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) { written.add(line) }
        override fun destroy() { lines.close() }
        fun bind(sessionId: String) = runBlocking { feed("""{"type":"system","subtype":"init","session_id":"$sessionId"}""") }
        fun errorUnbound() = runBlocking { feed("""{"type":"result","subtype":"error_during_execution","is_error":true}""") }
    }

    /** Records every spawn command and hands out pre-seeded processes in order. */
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
        // Scope by the full key so the connector-level M10 test actually exercises projectId routing.
        override fun find(projectId: String, agentId: String): SessionEntry? =
            entry?.takeIf { it.projectId == projectId && it.agentId == agentId }
        override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) {
            upserts.add(sessionId); entry = SessionEntry(projectId, agentId, sessionId, now, now)
        }
        override fun clear(projectId: String, agentId: String) { clears.incrementAndGet(); entry = null }
        fun sessionId() = entry?.sessionId
    }

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    private fun connector(
        spawner: RecordingSpawner,
        store: FakeSessionStore,
        projectId: String = "default",
    ): ClaudeCodeConnector {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val tmp = Files.createTempDirectory("resume-cwd").toFile()
        return ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = tmp,
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sessionStore = store,
            projectIdOf = { projectId },
            clock = { 12345L },
        )
    }

    private suspend fun await(timeoutMs: Long = 3000, cond: () -> Boolean) =
        withTimeout(timeoutMs) { while (!cond()) delay(10) }

    // ---- read-before-spawn / write-after-init ----

    /** M1 + M4 + M3: no entry ⇒ no `--resume`; nothing written before init; the bound id is persisted after. */
    @Test
    fun freshStart_noResumeFlag_writesAfterInit() = runBlocking {
        val proc = FakeProc()
        val spawner = RecordingSpawner(proc)
        val store = FakeSessionStore()
        val session = connector(spawner, store).open("backend")
        scope.launch { session.events.collect { } } // activate the reader

        assertFalse(spawner.hasResume(0), "M1: first start has no --resume")
        assertNull(store.sessionId(), "M4: nothing persisted before system/init")

        proc.bind("sess-new")
        await { store.sessionId() == "sess-new" }
        assertTrue(store.upserts.contains("sess-new"), "M3: the bound id is persisted write-after-init")
    }

    /** M2: a durable entry ⇒ the first spawn prepends `--resume <id>`. */
    @Test
    fun entryPresent_prependsResumeFlag() = runBlocking {
        val proc = FakeProc()
        val spawner = RecordingSpawner(proc)
        val store = FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L))
        connector(spawner, store).open("backend")
        await { spawner.count() == 1 }
        assertTrue(spawner.hasResume(0), "M2: an existing entry yields --resume")
        assertEquals("sess-1", spawner.commands[0][spawner.commands[0].indexOf("--resume") + 1])
    }

    /** M10 (connector): a different active projectId does NOT find another project's entry ⇒ no --resume. */
    @Test
    fun differentProject_doesNotResume() = runBlocking {
        val proc = FakeProc()
        val spawner = RecordingSpawner(proc)
        val store = FakeSessionStore(SessionEntry("projA", "backend", "sess-A", 1L, 1L))
        connector(spawner, store, projectId = "projB").open("backend") // find("projB",…) == null in this fake
        await { spawner.count() == 1 }
        assertFalse(spawner.hasResume(0), "M10: projectId scopes the key — projB must not resume projA")
    }

    // ---- the resume facade ----

    /** Happy resume: the resumed id binds on attempt #1 ⇒ no clear, no respawn. */
    @Test
    fun resumeSucceeds_noFallback() = runBlocking {
        val p1 = FakeProc()
        val spawner = RecordingSpawner(p1)
        val store = FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L))
        val session = connector(spawner, store).open("backend")
        scope.launch { session.events.collect { } }
        p1.bind("sess-1")
        await { store.sessionId() == "sess-1" && store.upserts.contains("sess-1") }
        delay(100) // give any (wrong) fallback a chance to fire
        assertEquals(0, store.clears.get(), "a successful resume never clears")
        assertEquals(1, spawner.count(), "a successful resume never respawns")
    }

    /** M5 + M6 + M7: a stale id dies unbound ⇒ clear once + respawn fresh WITHOUT the flag, exactly once. */
    @Test
    fun staleResume_clearsAndRespawnsFreshOnce() = runBlocking {
        val p1 = FakeProc()
        val p2 = FakeProc()
        val spawner = RecordingSpawner(p1, p2)
        val store = FakeSessionStore(SessionEntry("default", "backend", "sess-stale", 1L, 1L))
        val session = connector(spawner, store).open("backend")
        scope.launch { session.events.collect { } }
        assertTrue(spawner.hasResume(0), "attempt #1 carried --resume")

        p1.errorUnbound(); p1.destroy() // stale id → is_error while unbound → DIED_UNBOUND
        await { spawner.count() == 2 }

        assertTrue(store.clears.get() >= 1, "M5: the stale entry is cleared")
        assertFalse(spawner.hasResume(1), "M6: the fresh respawn has NO --resume")

        p2.destroy() // the fresh attempt also dies → must NOT trigger a 3rd spawn
        delay(150)
        assertEquals(2, spawner.count(), "M7: the fallback fires exactly once (no third attempt)")
    }

    /**
     * R2 (M5b + M7b): an error / death AFTER the session bound is a mid-session crash (CYP-73), NOT a
     * resume failure — it must NOT clear the durable entry and must NOT respawn. This is the guard that
     * keeps the NEXT real restart resumable.
     */
    @Test
    fun postBindCrash_doesNotClearOrRespawn() = runBlocking {
        val p1 = FakeProc()
        val p2 = FakeProc()
        val spawner = RecordingSpawner(p1, p2)
        val store = FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L))
        val session = connector(spawner, store).open("backend")
        scope.launch { session.events.collect { } }

        p1.bind("sess-1")
        await { store.sessionId() == "sess-1" }

        p1.errorUnbound(); p1.destroy() // error + death AFTER bind = mid-session crash
        delay(200)

        assertEquals(0, store.clears.get(), "R2: a post-bind crash must NOT clear the durable entry")
        assertEquals(1, spawner.count(), "R2: a post-bind crash must NOT respawn")
        assertEquals("sess-1", store.sessionId(), "the binding stays intact for the next real restart")
    }

    /** R3: closing in the pre-bind window ⇒ no spurious clear, no respawn, and sendTurn does not hang. */
    @Test
    fun preBindClose_noSpuriousClear_noHang() = runBlocking {
        val p1 = FakeProc()
        val p2 = FakeProc()
        val spawner = RecordingSpawner(p1, p2)
        val store = FakeSessionStore(SessionEntry("default", "backend", "sess-1", 1L, 1L))
        val session: ConnectorSession = connector(spawner, store).open("backend")

        session.close() // close BEFORE anything binds
        delay(150)

        assertEquals(0, store.clears.get(), "R3: a pre-bind close must NOT clear (clear is only for a real stale resume)")
        assertEquals(1, spawner.count(), "R3: a pre-bind close must NOT respawn")
        // sendTurn must not HANG on the ready-gate after close: a real timeout fails the test, while a fast
        // cancellation (the gate was cancelled) is the expected, no-hang outcome.
        try {
            withTimeout(1000) { session.sendTurn(UserTurn("x")) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("R3: sendTurn hung on the ready-gate after close", e)
        } catch (expected: Exception) {
            // ready-gate cancelled → sendTurn fails fast (no hang). Good.
        }
    }
}
