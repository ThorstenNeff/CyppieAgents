package com.tneff.cyppieagents.pty

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-332 — the PTY through-line driven through a **REAL pty4j PTY** (bundled native libs) running a **fake
 * interactive TUI** (a bash `while read` loop) — the strongest end-to-end test, mirroring the real-
 * `ProcessBuilderSpawner` approach of the resume tests (a mock would prove nothing about the real PTY).
 *
 * The fake echoes `GOT:<line>` for input, and prints `stty size` (`rows cols`) for the literal `SIZE` — so
 * a resize is asserted against the REAL window size the child sees (mutation-sharp: a no-op resize keeps
 * the initial 24×80).
 */
class PtyManagerTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do
          if [ "${'$'}line" = "SIZE" ]; then stty size; else printf 'GOT:%s\n' "${'$'}line"; fi
        done
    """.trimIndent()

    private fun fakeTui(): File {
        val f = Files.createTempFile("fake-tui", ".sh").toFile()
        f.writeText(FAKE_TUI); f.setExecutable(true); return f
    }

    private fun manager(cmd: File) = PtyManager(
        worktreeDirOf = { Files.createTempDirectory("cyp332-wt").toFile() },
        resolveApiKey = { null },
        scope = scope,
        command = listOf(cmd.absolutePath),
    )

    private class Sink { val sb = StringBuilder(); fun text() = synchronized(sb) { sb.toString() }
        val append: (ByteArray) -> Unit = { b -> synchronized(sb) { sb.append(String(b)) } } }

    private suspend fun awaitContains(sink: Sink, needle: String) =
        withTimeout(15_000) { while (!sink.text().contains(needle)) delay(50) }

    @Test
    fun spawn_input_reachesTheProcess_andOutput_streamsBack() = runBlocking {
        val mgr = manager(fakeTui())
        val sink = Sink()
        val handle = mgr.open("backend", 80, 24, onOutput = sink.append, onExit = {})
        handle.write("hello\n".toByteArray())
        awaitContains(sink, "GOT:hello") // input reached the child AND its stdout reached us
        assertTrue(sink.text().contains("GOT:hello"))
        mgr.close("backend")
    }

    @Test
    fun resize_changesTheWindowSizeTheChildSees() = runBlocking {
        val mgr = manager(fakeTui())
        val sink = Sink()
        val handle = mgr.open("backend", 80, 24, onOutput = sink.append, onExit = {})
        handle.resize(120, 40) // cols=120, rows=40 → SIGWINCH
        handle.write("SIZE\n".toByteArray())
        awaitContains(sink, "40 120") // `stty size` prints `rows cols` = 40 120 (NOT the initial 24 80)
        assertTrue(sink.text().contains("40 120"), "the child saw the resized 40x120 window, not the initial 24x80")
        mgr.close("backend")
    }

    @Test
    fun secondOpen_whileLive_isRejected_singleFlightPerAgent() = runBlocking {
        val mgr = manager(fakeTui())
        mgr.open("backend", 80, 24, onOutput = {}, onExit = {})
        assertTrue(mgr.isLive("backend"))
        // §4.1: a second PTY for the SAME agent is rejected — never two rival interactive processes.
        assertFailsWith<PtyBusyException> { mgr.open("backend", 80, 24, onOutput = {}, onExit = {}) }
        // …but a DIFFERENT agent may open (single-flight is per-agent, not global).
        mgr.open("frontend", 80, 24, onOutput = {}, onExit = {})
        assertTrue(mgr.isLive("frontend"))
        mgr.close("backend"); mgr.close("frontend")
    }

    @Test
    fun close_firesOnExitOnce_freesTheSlot_andReopenWorks() = runBlocking {
        val mgr = manager(fakeTui())
        val exited = CompletableDeferred<Int>()
        mgr.open("backend", 80, 24, onOutput = {}, onExit = { exited.complete(it) })
        assertTrue(mgr.isLive("backend"))
        mgr.close("backend")
        withTimeout(15_000) { exited.await() } // teardown → onExit fired (the single "process gone" source)
        assertFalse(mgr.isLive("backend"), "the slot is freed after teardown")
        // single-flight cleared → the agent can be re-opened (no lingering claim)
        mgr.open("backend", 80, 24, onOutput = {}, onExit = {})
        assertTrue(mgr.isLive("backend"))
        mgr.close("backend")
    }

    @Test
    fun processThatExitsOnItsOwn_firesOnExit_andFreesTheSlot() = runBlocking {
        // A command that exits immediately (no read loop) → EOF → onExit fires without an explicit close.
        val script = Files.createTempFile("exit-now", ".sh").toFile().apply {
            writeText("#!/usr/bin/env bash\nexit 7\n"); setExecutable(true)
        }
        val mgr = manager(script)
        val exited = CompletableDeferred<Int>()
        mgr.open("backend", 80, 24, onOutput = {}, onExit = { exited.complete(it) })
        val code = withTimeout(15_000) { exited.await() }
        assertEquals(7, code, "the child's real exit code is reported")
        assertFalse(mgr.isLive("backend"), "an on-its-own exit frees the slot too")
    }
}
