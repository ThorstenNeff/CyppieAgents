package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-879 (OS-A, Compose mirror of web-ts CYP-868 `orchestrationMessage.test.ts`) — the pure orchestration model:
 * server-stamped meta ONLY (render ≠ authority). Absent ⇒ NOTE (never a fabricated TASK/STATUS); a reply link only
 * from `inReplyTo` to a PRESENT parent (orphan ⇒ no thread); depth cycle- and runaway-safe.
 */
class OrchestrationMessageTest {

    private fun msg(id: String, meta: MessageMeta? = null) = Message(id, "c", "po", "b-$id", 0L, meta)

    // messageKind — server-stamped only; absent ⇒ NOTE, never fabricated ------------------------------------------

    @Test
    fun explicitKinds_passThroughVerbatim() {
        assertEquals(MessageKind.TASK, messageKind(MessageMeta(kind = MessageKind.TASK)))
        assertEquals(MessageKind.STATUS, messageKind(MessageMeta(kind = MessageKind.STATUS)))
        assertEquals(MessageKind.NOTE, messageKind(MessageMeta(kind = MessageKind.NOTE)))
    }

    @Test
    fun absentMeta_orAbsentKind_isNOTE_neverFabricated() {
        // The honesty core. MUT: default absent → TASK/STATUS (client-fabricated type) → reds.
        assertEquals(MessageKind.NOTE, messageKind(null)) // no meta
        assertEquals(MessageKind.NOTE, messageKind(MessageMeta())) // meta present, kind null
        assertEquals(MessageKind.NOTE, messageKind(MessageMeta(inReplyTo = "x"))) // has inReplyTo, no kind → still NOTE
    }

    @Test
    fun isOrchestrationKind_TASKandSTATUS_areBadged_NOTE_isQuiet() {
        assertTrue(isOrchestrationKind(MessageKind.TASK))
        assertTrue(isOrchestrationKind(MessageKind.STATUS))
        assertFalse(isOrchestrationKind(MessageKind.NOTE)) // MUT: badge NOTE too → reds
    }

    // replyParent — server inReplyTo only; unknown parent ⇒ NOT a fabricated thread --------------------------------

    @Test
    fun replyParent_toPresentParent_returnsIt_topLevel_isNull() {
        val parent = msg("p")
        val reply = msg("r", MessageMeta(inReplyTo = "p"))
        val byId = indexById(listOf(parent, reply))
        assertEquals("p", replyParent(reply, byId)?.id) // reply → present parent
        assertNull(replyParent(parent, byId)) // no inReplyTo → top-level, not a reply
    }

    @Test
    fun replyParent_toAbsentParent_isNull_neverFabricated() {
        // MUT: return a placeholder / treat as a reply anyway → fabricates a thread we cannot show → reds.
        val orphan = msg("o", MessageMeta(inReplyTo = "ghost"))
        assertNull(replyParent(orphan, indexById(listOf(orphan))))
    }

    // replyDepth — server chain only; cycle- and runaway-safe ------------------------------------------------------

    @Test
    fun replyDepth_followsTheServerChain_topLevel0_reply1_replyToReply2() {
        val a = msg("a")
        val b = msg("b", MessageMeta(inReplyTo = "a"))
        val c = msg("c", MessageMeta(inReplyTo = "b"))
        val byId = indexById(listOf(a, b, c))
        assertEquals(0, replyDepth(a, byId))
        assertEquals(1, replyDepth(b, byId))
        assertEquals(2, replyDepth(c, byId))
    }

    @Test
    fun replyDepth_absentParent_is0_noFabricatedAncestry() {
        val orphan = msg("o", MessageMeta(inReplyTo = "ghost"))
        assertEquals(0, replyDepth(orphan, indexById(listOf(orphan))))
    }

    @Test
    fun replyDepth_cyclicChain_terminatesViaVisitedSet_atExactlyOne() {
        // Untrusted server data: an a↔b cycle. The VISITED-SET stops it after b (a is already seen) ⇒ depth EXACTLY 1.
        // MUT: drop the visited-set → the cap alone would let it climb to MAX_REPLY_DEPTH (8 ≠ 1) → reds. (Dropping
        // BOTH guards would loop forever; asserting the exact value pins the visited-set without relying on a hang.)
        val a = msg("a", MessageMeta(inReplyTo = "b"))
        val b = msg("b", MessageMeta(inReplyTo = "a"))
        val byId = indexById(listOf(a, b))
        assertEquals(1, replyDepth(a, byId))
        assertTrue(replyDepth(a, byId) <= MAX_REPLY_DEPTH)
    }

    @Test
    fun replyDepth_chainLongerThanMax_isCappedAtMax() {
        // A straight (acyclic) chain longer than the cap: m0 ← m1 ← … ← m10. The RUNAWAY CAP clamps the indent to
        // MAX_REPLY_DEPTH (8), not the true 10. MUT: drop/loosen the cap → depth climbs to 10 (≠ 8) → reds.
        val msgs = buildList {
            add(msg("m0"))
            for (i in 1..10) add(msg("m$i", MessageMeta(inReplyTo = "m${i - 1}")))
        }
        val byId = indexById(msgs)
        assertEquals(MAX_REPLY_DEPTH, replyDepth(msgs.last(), byId))
    }
}
