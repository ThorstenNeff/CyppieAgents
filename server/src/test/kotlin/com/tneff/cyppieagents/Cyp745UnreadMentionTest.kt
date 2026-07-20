package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.InMemoryReadCursorStore
import com.tneff.cyppieagents.comm.TargetedReadState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-745 (Notify Phase-2) mutation teeth — `ChannelReadState.hasUnreadMention` / `ReadStateEvent.hasUnreadMention`:
 * "does an UNREAD message in this channel mention ME?", server-computed, self-only.
 *
 * Every test names the mutant of `Hub.readStateOf` it dies against. The sharp ones are deliberately the
 * NEGATIVE cases — a naive "I was mentioned ⇒ true" positive test stays green even if the feature is gutted
 * to `hasUnreadMention = unreadCount > 0`, so it proves nothing on its own.
 */
class Cyp745UnreadMentionTest {
    private fun newHub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        return Hub(state, InMemoryMessageStore(), readCursors = InMemoryReadCursorStore())
    }

    private fun mentionFlag(hub: Hub, subject: String, channelId: String): Boolean =
        hub.readState(subject).first { it.channelId == channelId }.hasUnreadMention

    // Baseline (non-vacuity anchor for the negatives below): an unread message that DOES mention the viewer
    // raises the flag. Alone this proves little — its job is to show the negatives aren't green-by-inertia.
    @Test fun unreadMentionOfMeRaisesTheFlag() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "seed")
        hub.markRead("backend", "po-backend", m1.seq)
        hub.postAsAgent("po", "po-backend", "please look @backend")
        assertTrue(mentionFlag(hub, "backend", "po-backend"), "an unread message mentioning the viewer must raise the flag")
    }

    // ★ THE SHARP ONE: an unread message mentioning SOMEONE ELSE must NOT raise the viewer's flag.
    // Mutant: `hasUnreadMention = unread.isNotEmpty()` (i.e. drop the mentionsYou term entirely) → reds.
    // The positive test above survives that mutant; this one does not — which is why it carries the feature.
    @Test fun unreadMentionOfSomeoneElseDoesNotRaiseMyFlag() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "seed")
        hub.markRead("backend", "po-backend", m1.seq)
        hub.postAsAgent("po", "po-backend", "note for @po only") // unread for backend, but mentions po
        val rs = hub.readState("backend").first { it.channelId == "po-backend" }
        assertEquals(1, rs.unreadCount, "the message IS unread for backend (so the flag is the only thing under test)")
        assertFalse(rs.hasUnreadMention, "a mention of another participant must not notify this viewer")
    }

    // The cursor axis: a mention BELOW the cursor is already read ⇒ no flag.
    // Mutant: evaluate mentions over the unfiltered channel history (drop `seq > cursor`) → reds.
    @Test fun mentionBelowTheCursorIsAlreadyRead() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "hey @backend")
        assertTrue(mentionFlag(hub.also { it.markRead("backend", "po-backend", m1.seq - 1) }, "backend", "po-backend"), "before the cursor covers it, the mention is unread")
        hub.markRead("backend", "po-backend", m1.seq) // now caught up past the mention
        assertFalse(mentionFlag(hub, "backend", "po-backend"), "a mention at/below the cursor is read ⇒ no flag")
    }

    // Own sends never notify their sender, even when the sender writes their own name.
    // Mutant: drop the `from != subject` filter → the self-mention raises backend's own flag → reds.
    @Test fun ownSendMentioningMyselfDoesNotNotifyMe() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "seed")
        hub.markRead("backend", "po-backend", m1.seq)
        hub.postAsAgent("backend", "po-backend", "reminder to @backend from myself")
        val rs = hub.readState("backend").first { it.channelId == "po-backend" }
        assertEquals(0, rs.unreadCount, "your own send is not unread for you")
        assertFalse(rs.hasUnreadMention, "your own send must never notify you, self-mention included")
    }

    // Recognition rides the ONE MentionResolver pass (code regions masked) — NOT a re-implemented `contains("@id")`.
    // Mutant: replace the resolver call with `it.body.contains("@$subject")` → the fenced mention reds this.
    @Test fun mentionInsideACodeFenceIsNotAMention() {
        val hub = newHub()
        val m1 = hub.postAsAgent("po", "po-backend", "seed")
        hub.markRead("backend", "po-backend", m1.seq)
        hub.postAsAgent("po", "po-backend", "sample:\n```\nping @backend\n```\ndone")
        val rs = hub.readState("backend").first { it.channelId == "po-backend" }
        assertEquals(1, rs.unreadCount, "the message is unread (the flag is the only thing under test)")
        assertFalse(rs.hasUnreadMention, "an @id inside a fenced code block is not a mention (single-recognition)")
    }

    // ★ PO-REQUESTED CROSS-FIELD INVARIANT: `hasUnreadMention == true` with `unreadCount == 0` must be
    // UNREACHABLE. Both fields come from ONE materialized filter pass in `Hub.readStateOf`, so the flag can
    // only be raised by an element of the very set whose size is the count.
    // Mutant (the two-pass drift bug this shape forecloses): compute `mentionsMe` over the UNFILTERED
    // `store.byChannel(channelId)` instead of the `unread` set → here the read mention raises the flag while
    // the count is 0 → reds. A test that cannot be reddened this way would be vacuous.
    @Test fun mentionFlagCanNeverOutliveTheUnreadCount() {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "first @backend")
        val m2 = hub.postAsAgent("po", "po-backend", "second @backend")
        hub.markRead("backend", "po-backend", m2.seq) // fully caught up: every mention is now READ
        val rs = hub.readState("backend").first { it.channelId == "po-backend" }
        assertEquals(0, rs.unreadCount, "the channel is fully read")
        assertFalse(rs.hasUnreadMention, "unreadCount == 0 ⇒ hasUnreadMention must be false (single-pass invariant)")
        assertFalse(
            rs.hasUnreadMention && rs.unreadCount == 0,
            "the (true, 0) combination is structurally unreachable — the flag rides the counted set itself",
        )
    }

    // The live delta carries the flag, per viewer, self-only: the same post makes it true for the mentioned
    // viewer and false for the other — proving it is computed per subject, not once and broadcast.
    // Mutant: emit one shared ReadStateEvent for all members → both viewers get the same flag → reds.
    @Test fun readStateEventCarriesThePerViewerFlag() = runBlocking {
        val hub = newHub()
        // Both viewers need a cursor, else they stay UNKNOWN (omitted) and receive no delta.
        val seed = hub.postAsAgent("frontend", "po-frontend", "seed")
        val seedB = hub.postAsAgent("backend", "po-backend", "seed")
        hub.markRead("po", "po-frontend", seed.seq)
        hub.markRead("po", "po-backend", seedB.seq)
        hub.markRead("backend", "po-backend", seedB.seq)

        val seen = mutableListOf<TargetedReadState>()
        val job = launch { hub.readStateEvents.collect { seen.add(it) } }
        yield()
        hub.postAsAgent("po", "po-backend", "over to you @backend") // po posts; backend is mentioned
        delay(50)
        job.cancel()

        val toBackend = seen.filter { it.subject == "backend" && it.event.channelId == "po-backend" }
        assertTrue(toBackend.isNotEmpty(), "the mentioned viewer must receive a read-state delta")
        assertTrue(toBackend.last().event.hasUnreadMention, "backend was mentioned ⇒ their delta carries the flag")
        assertTrue(
            seen.none { it.subject == "po" && it.event.channelId == "po-backend" },
            "the sender gets no delta for their own post (self-excluded), so no foreign flag is emitted",
        )
    }
}
