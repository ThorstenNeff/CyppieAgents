package com.tneff.cyppieagents.model

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-745 — `hasUnreadMention` is a **required** wire field on BOTH read-state carriers
 * ([ChannelReadState] REST + [ReadStateEvent] `/ws/comm`), following the CYP-498 [AuthMe.verified] precedent.
 *
 * **Why this is load-bearing.** A defaulted `Boolean` WITHOUT `@Required` may be omitted by a terse serializer
 * and lands OPTIONAL in the generated contract. A PRESENT channel could then arrive with no field, and both
 * client readings are wrong:
 *  - `undefined → false` = a **fabricated all-clear** (the UIUX2 §8① collapse the ticket exists to prevent);
 *  - `undefined → UNKNOWN` = a SECOND unknown axis beside channel-presence, which is meant to be the only one.
 *
 * `@Required` gives (a) always-on-the-wire, independent of `encodeDefaults`, and (b) `isElementOptional == false`
 * in the descriptor — the exact flag `SchemaWalker` reads to put the field in the contract's `required` set. One
 * annotation drives both, so wire and schema cannot drift.
 *
 * If a future change drops `@Required`, every test here reds.
 */
class Cyp745HasUnreadMentionRequiredTest {

    // --- descriptor (this is what the OpenAPI/AsyncAPI `required` set is generated from) ---

    @Test
    fun hasUnreadMention_isNonOptional_onChannelReadState() {
        val d = ChannelReadState.serializer().descriptor
        val i = d.getElementIndex("hasUnreadMention")
        assertTrue(i >= 0, "hasUnreadMention must exist in the ChannelReadState descriptor")
        assertFalse(d.isElementOptional(i), "must be NON-optional — the contract `required` entry derives from this")
    }

    @Test
    fun hasUnreadMention_isNonOptional_onReadStateEvent() {
        val d = ReadStateEvent.serializer().descriptor
        val i = d.getElementIndex("hasUnreadMention")
        assertTrue(i >= 0, "hasUnreadMention must exist in the ReadStateEvent descriptor")
        assertFalse(d.isElementOptional(i), "must be NON-optional — the WS contract `required` entry derives from this")
    }

    // The whole point of the annotation: NO optional field may exist on these carriers, so a present channel
    // can never arrive field-less. Guards the set as a whole rather than one name — a future added-and-defaulted
    // field would reopen exactly the hole this ticket closed, and this reds on it.
    @Test
    fun readStateCarriers_haveNoOptionalFieldsAtAll() {
        for (d in listOf(ChannelReadState.serializer().descriptor, ReadStateEvent.serializer().descriptor)) {
            val optional = (0 until d.elementsCount).filter { d.isElementOptional(it) }.map { d.getElementName(it) }
            assertEquals(
                emptyList(), optional,
                "${d.serialName}: every read-state field must be contract-required (UNKNOWN rides channel PRESENCE, " +
                    "never a missing field) — optional found: $optional",
            )
        }
    }

    // --- wire presence (independent of any serializer's encodeDefaults) ---

    @Test
    fun hasUnreadMention_alwaysOnWire_evenFalse_underTerseJson() {
        // encodeDefaults = false is the kotlinx default and WOULD drop a plain defaulted field. @Required overrides.
        val terse = Json { encodeDefaults = false }
        val rest = terse.encodeToString(ChannelReadState.serializer(), ChannelReadState("po-backend", 7L, 0))
        val ws = terse.encodeToString(ReadStateEvent.serializer(), ReadStateEvent("po-backend", 7L, 0))
        assertTrue("\"hasUnreadMention\"" in rest, "must survive a terse serializer on ChannelReadState: $rest")
        assertTrue("\"hasUnreadMention\"" in ws, "must survive a terse serializer on ReadStateEvent: $ws")
    }

    @Test
    fun hasUnreadMention_onWire_underCommJson_inBothStates() {
        val clear = CommJson.encodeToString(ChannelReadState.serializer(), ChannelReadState("po-backend", 7L, 0))
        assertTrue("\"hasUnreadMention\":false" in clear, "an authoritative all-clear must SAY false, not omit it: $clear")
        val flagged = CommJson.encodeToString(ChannelReadState.serializer(), ChannelReadState("po-backend", 7L, 3, true))
        assertTrue("\"hasUnreadMention\":true" in flagged, "the flagged state must carry true: $flagged")
    }

    // A field-less payload must be a DECODE FAILURE, not a silent `false`. This is the client-side half of the
    // invariant: with @Required, kotlinx refuses the input rather than materializing a fabricated all-clear.
    @Test
    fun aPayloadMissingTheField_failsToDecode_ratherThanDefaultingToFalse() {
        val fieldLess = """{"channelId":"po-backend","lastReadSeq":7,"unreadCount":3}"""
        val threw = runCatching { CommJson.decodeFromString(ChannelReadState.serializer(), fieldLess) }.isFailure
        assertTrue(
            threw,
            "a present channel without hasUnreadMention must FAIL to decode — silently reading it as false is the " +
                "fabricated all-clear this annotation exists to prevent",
        )
    }
}
