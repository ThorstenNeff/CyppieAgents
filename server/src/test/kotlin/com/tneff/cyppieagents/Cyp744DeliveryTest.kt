package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.MentionSpan
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-744 §3-i / §3-iii — the two OBJECT teeth of the DeliveredMessage delivery:
 *  (i) **§9-frame-guard:** the `/ws/hub` `WireMessage` frame (the BYOA agent wire) carries NO mention field — the
 *      spans NEVER reach an agent connector. Proven by serializing the ACTUAL frame (`WireEnvelope(WireMessage)`,
 *      the exact form HubWireRoutes sends), not by convention.
 *  (iii) **seq-preservation:** the wrapper carries `message.seq` intact (incl. the `0`=unassigned sentinel) → CYP-705's
 *      unread line + `upToSeq` cursor stay unbroken. A flatten that dropped/renamed `seq` would break 705 SILENTLY.
 */
class Cyp744DeliveryTest {

    private val msg = Message(id = "m1", channelId = "po-frontend", from = "frontend", body = "@po hi", ts = 1L, seq = 7L)
    private val spans = listOf(MentionSpan(0, 3, "po"))

    @Test fun ninthGuard_wireMessageFrameCarriesNoMentionField() {
        // The EXACT /ws/hub frame (HubWireRoutes:110). WireMessage embeds the bare Message → no enrichment on the wire.
        val wireJson = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, WireMessage(msg)))
        assertFalse(wireJson.contains("mention"), "the /ws/hub WireMessage frame must NOT carry mention spans: $wireJson")
        // Mutation: put a `mentions` field on the stored Message (the §9 wound) → it appears in this frame → RED.
    }

    @Test fun frontendMessageEventCarriesTheSpans() {
        // The /ws/comm frame DOES carry the Display spans (that's the whole point of the frontend wrapper).
        val commJson = CommJson.encodeToString(CommWsServerEvent.serializer(), MessageEvent(DeliveredMessage(msg, spans)))
        assertTrue(commJson.contains("mentions"), "the /ws/comm frame must carry the Display spans: $commJson")
        assertTrue(commJson.contains("\"po\""))
    }

    @Test fun seqPreservation_wrapperKeepsMessageSeqIntact() {
        assertEquals(7L, DeliveredMessage(msg, spans).message.seq)          // a real seq survives the wrapper
        assertEquals(0L, DeliveredMessage(msg.copy(seq = 0L)).message.seq)  // the 0=unassigned sentinel survives too
        // Mutation: a flatten/transform that drops or renames `seq` → 705's cursor breaks SILENTLY → this reds.
    }
}
