package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.InMemoryReadCursorStore
import com.tneff.cyppieagents.comm.TargetedReadState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-705 read-state mutation teeth — the pinned AC (Assist2 F1/F2/F3) + Tester2's 3 functional points.
 * Each test is red-provable against a named mutant of the Hub read-state logic.
 */
class Cyp705ReadStateTest {
    private fun newHub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        return Hub(state, InMemoryMessageStore(), readCursors = InMemoryReadCursorStore())
    }

    // Absence = UNKNOWN: a channel with NO cursor is OMITTED from read-state (never a fabricated 0).
    // Mutant: emit a 0-cursor entry for cursorless channels → this test reds.
    @Test fun absenceIsUnknownNotZero() {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "hi")
        assertNull(
            hub.readState("backend").firstOrNull { it.channelId == "po-backend" },
            "a channel with no cursor must be ABSENT (UNKNOWN), not present with count 0",
        )
    }

    // Server-computed unread past the cursor; own sends excluded (from != subject).
    // Mutant: drop the `from != subject` filter → own message inflates the count → reds.
    @Test fun unreadCountsPastCursorExcludingOwnSends() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "one")
        val m2 = hub.postAsAgent("po", "po-backend", "two")
        hub.markRead("backend", "po-backend", m1.seq) // cursor = m1
        assertEquals(1, hub.readState("backend").first { it.channelId == "po-backend" }.unreadCount, "only m2 is past m1")
        val m3 = hub.postAsAgent("backend", "po-backend", "my own") // backend's OWN send
        assertEquals(1, hub.readState("backend").first { it.channelId == "po-backend" }.unreadCount, "the sender's own send must not raise their unread (still just m2)")
        assertEquals(0, hub.markRead("backend", "po-backend", m3.seq).unreadCount, "caught up through the latest seq ⇒ confirmed-read 0")
    }

    // Monotonic advance-only: a lower/late upToSeq is a no-op (never regress).
    // Mutant: `set` instead of `max` → cursor regresses → reds.
    @Test fun markReadIsMonotonic() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "a")
        val m2 = hub.postAsAgent("po", "po-backend", "b")
        hub.markRead("backend", "po-backend", m2.seq)
        val rs = hub.markRead("backend", "po-backend", m1.seq) // lower → no-op
        assertEquals(m2.seq, rs.lastReadSeq, "a lower upToSeq must not regress the cursor")
    }

    // Tester2 (2) / PL: unreadCount is a server-computed COUNT of `seq > lastReadSeq` — STRUCTURALLY >= 0, NOT
    // a clamp. There is no subtraction, no client decrement, no incrementally-maintained field (that would be
    // the two-source class that can go negative). A cursor beyond max seq ⇒ zero messages qualify ⇒ 0.
    // Mutant: compute unread as `maxSeq - lastReadSeq` (a subtraction) → this goes negative here → reds.
    @Test fun unreadIsAStructuralCountNeverNegative() {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "x")
        assertEquals(0, hub.markRead("backend", "po-backend", 999_999L).unreadCount)
    }

    // F3: canRead is checked BEFORE any write/state-read, uniform 403 — a non-member and a nonexistent
    // channel both throw the SAME ForbiddenException (no existence oracle, no cursor/seq leak).
    @Test fun markReadFailsClosedUniformly() {
        val hub = newHub()
        assertFailsWith<ForbiddenException> { hub.markRead("frontend", "po-backend", 1L) } // not a member
        assertFailsWith<ForbiddenException> { hub.markRead("backend", "no-such-channel", 1L) } // channel absent
    }

    // F1 (highest risk): the read-state delta is routed by VIEWER IDENTITY. markRead emits a TargetedReadState
    // whose subject is the marker — self-only. Mutant: fan out to all canRead members → a foreign subject
    // appears (cross-viewer read-receipt leak) → reds.
    @Test fun readStateEventIsSelfOnly() = runBlocking {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "hi")
        val seen = mutableListOf<TargetedReadState>()
        val job = launch { hub.readStateEvents.collect { seen.add(it) } }
        yield()
        hub.markRead("backend", "po-backend", 1L)
        delay(50)
        job.cancel()
        assertTrue(seen.any { it.subject == "backend" && it.event.channelId == "po-backend" }, "backend's own mark-read must target backend")
        assertTrue(seen.all { it.subject == "backend" }, "no foreign subject may receive backend's mark-read delta (self-only)")
    }
}
