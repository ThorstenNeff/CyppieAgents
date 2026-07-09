package com.tneff.cyppieagents.terminal

import com.jediterm.core.util.TermSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-334 — the JediTerm ↔ PTY-over-WS bridge ([WsTtyConnector]), tested headlessly (no Swing): the write/resize
 * mapping to the session, and the byte→UTF-8-char read path with EOF on the session's `incoming` completing.
 */
class WsTtyConnectorTest {

    private class FakeSession(override val incoming: Flow<ByteArray>) : TerminalSession {
        val sent = mutableListOf<ByteArray>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        var closed = false
            private set
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun resize(cols: Int, rows: Int) { resizes += (cols to rows) }
        override fun close() { closed = true }
    }

    @Test
    fun writeBytes_forwardToSessionSend() {
        val s = FakeSession(emptyFlow())
        WsTtyConnector(s, "t", CoroutineScope(Dispatchers.IO)).write(byteArrayOf(0x1B, 0x41))
        assertEquals(1, s.sent.size)
        assertEquals(listOf<Byte>(0x1B, 0x41), s.sent.single().toList())
    }

    @Test
    fun writeString_encodesUtf8ToSessionSend() {
        val s = FakeSession(emptyFlow())
        WsTtyConnector(s, "t", CoroutineScope(Dispatchers.IO)).write("é") // 2 UTF-8 bytes: 0xC3 0xA9
        assertEquals(listOf<Byte>(0xC3.toByte(), 0xA9.toByte()), s.sent.single().toList())
    }

    @Test
    fun resize_mapsColsAndRowsToSession() {
        val s = FakeSession(emptyFlow())
        WsTtyConnector(s, "t", CoroutineScope(Dispatchers.IO)).resize(TermSize(120, 40))
        assertEquals(120 to 40, s.resizes.single())
    }

    @Test
    fun name_isReported() {
        val s = FakeSession(emptyFlow())
        assertEquals("term-x", WsTtyConnector(s, "term-x", CoroutineScope(Dispatchers.IO)).name)
    }

    @Test
    fun incomingBytes_decodeAsUtf8Chars_thenEofOnCompletion() {
        // "hé\n": the é is a 2-byte UTF-8 rune — the byte-pipe + UTF-8 reader must decode it as ONE char, then the
        // completing flow closes the pipe so read() returns -1 (clean EOF → JediTerm ends the session).
        val s = FakeSession(flowOf("hé\n".encodeToByteArray()))
        val c = WsTtyConnector(s, "t", CoroutineScope(Dispatchers.IO))
        val out = StringBuilder()
        val buf = CharArray(32)
        while (true) {
            val n = c.read(buf, 0, buf.size) // blocks until bytes arrive, then -1 at EOF
            if (n == -1) break
            out.append(buf, 0, n)
        }
        assertEquals("hé\n", out.toString())
    }

    @Test
    fun multiByteRune_splitByteByByteAcrossFrames_reassemblesToOneCodepoint() {
        // The property the single-ByteArray test above could NOT prove (it emitted the whole rune in one frame, so a
        // per-frame `String(bytes, UTF_8)` decode would still pass — vacuous exactly here). This emits each byte of a
        // multi-byte rune as its OWN frame: é = C3 A9 (2 frames), 😀 = F0 9F 98 80 (4 frames). The byte-pipe + UTF-8
        // reader must reassemble across the frame boundaries; a per-frame decode would yield replacement chars.
        val s = FakeSession(
            flowOf(
                byteArrayOf(0xC3.toByte()), byteArrayOf(0xA9.toByte()), // é over 2 frames
                byteArrayOf(0xF0.toByte()), byteArrayOf(0x9F.toByte()),
                byteArrayOf(0x98.toByte()), byteArrayOf(0x80.toByte()), // 😀 over 4 frames
            ),
        )
        val c = WsTtyConnector(s, "t", CoroutineScope(Dispatchers.IO))
        val out = StringBuilder()
        val buf = CharArray(32)
        while (true) {
            val n = c.read(buf, 0, buf.size)
            if (n == -1) break
            out.append(buf, 0, n)
        }
        val text = out.toString()
        assertEquals("é😀", text, "runes split byte-by-byte across frames reassemble correctly")
        assertEquals(2, text.codePointCount(0, text.length), "two codepoints (é + 😀), never split into replacement chars")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun bytePump_runsOffTheCallerDispatcher_notOnTheEdt() {
        // Regression for the EDT-freeze (the pump's blocking `pipeOut.write` must NOT run on the caller's Compose/EDT
        // dispatcher, or a >64KB burst that fills the pipe blocks the UI thread). We model the EDT with a single-
        // thread dispatcher and pass it as the connector scope — the PROD dispatcher path, NOT an injected
        // Dispatchers.IO (which would HIDE the defect, [[prod-wiring-fixture-fidelity]]). We capture the thread the
        // pump actually collects on: with the fix (`launch(Dispatchers.IO)`) it is an IO worker, never "fake-edt".
        // (Thread-identity, not a blocking heartbeat — so a regression fails FAST and leaves no wedged pipe thread.)
        val edt = newSingleThreadContext("fake-edt")
        val pumpThread = CompletableDeferred<String>()
        val session = FakeSession(
            flow {
                pumpThread.complete(Thread.currentThread().name) // where the collector (pipe-write) actually runs
                awaitCancellation() // hold open; no pipe pressure, so cleanup is always clean
            },
        )
        val c = WsTtyConnector(session, "t", CoroutineScope(edt))
        try {
            val name = runBlocking { withTimeoutOrNull(3_000) { pumpThread.await() } }
            assertNotNull(name, "the pump started")
            assertFalse(name.contains("fake-edt"), "the byte-pump runs OFF the caller/EDT dispatcher (Dispatchers.IO), not on it")
        } finally {
            c.close()
            edt.close()
        }
    }

    @Test
    fun close_endsSession_andMarksDisconnected() {
        val scope = CoroutineScope(Dispatchers.IO)
        val s = FakeSession(flow { awaitCancellation() }) // never completes on its own → stays connected until close
        val c = WsTtyConnector(s, "t", scope)
        assertTrue(c.isConnected, "connected while the session streams")
        c.close()
        assertTrue(s.closed, "close() ends the underlying session")
        assertFalse(c.isConnected, "closed connector reports disconnected")
        scope.cancel()
    }
}
