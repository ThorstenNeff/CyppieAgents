package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireMessage
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-753 — test-honesty gap-map ②: the wire-discriminator `@SerialName` PINS.
 *
 * **Gap closed:** the sealed wire unions (`WireFrame`, `CommWsServerEvent`, `EventsWs*`) render a
 * `classDiscriminator = "type"` whose VALUE is each subtype's `@SerialName` — the cross-language wire
 * contract every non-Kotlin BYOA / relay consumer keys on. Yet no test pinned those literal strings: the
 * frames were exercised ONLY via Kotlin encode→decode round-trips, so a rename (`"deliver"`→`"delivery"`)
 * or a silently-added subtype passes GREEN on both Kotlin ends while breaking every external consumer (both
 * ends drift together). The pattern EXISTS elsewhere (`EventModelTest` pins `"type":"budget.suspected"`) —
 * it was just absent for these frames. `/ws/hub` (the public BYOA boundary) is the load-bearing one.
 *
 * Two complementary teeth per surface:
 *  - **completeness guard** — the sealed polymorphic descriptor's registered subtype discriminators must EQUAL
 *    a hardcoded literal set (a rename, an add, OR a remove reds — can't-miss-a-subtype).
 *  - **byte-render proof** — a real instance serialized through [CommJson] must carry `"type":"<name>"` in its
 *    bytes (and enum values must render as their wire string, not the Kotlin constant name).
 */
class Cyp753WireDiscriminatorPinsTest {

    /** The subtype discriminators registered on a sealed `@Serializable`'s polymorphic descriptor. For a sealed
     *  serializer the descriptor is `[ "type": String, "value": <union> ]`; the union's element names ARE the
     *  subtypes' `@SerialName`s — i.e. exactly what becomes the wire `"type"` value. */
    private fun subtypeDiscriminators(desc: SerialDescriptor): Set<String> {
        val union = desc.getElementDescriptor(1)
        return (0 until union.elementsCount).map { union.getElementName(it) }.toSet()
    }

    /** An enum descriptor's element names are its constants' `@SerialName`s (the wire strings). */
    private fun enumWireNames(desc: SerialDescriptor): Set<String> =
        (0 until desc.elementsCount).map { desc.getElementName(it) }.toSet()

    private fun wire(frame: WireFrame): String = CommJson.encodeToString(WireFrame.serializer(), frame)

    // ---------------- /ws/hub — the public BYOA boundary (worst first) ----------------

    @Test
    fun hub_wireFrame_discriminators_arePinned() {
        assertEquals(
            setOf("hello", "send", "subscribe", "event", "ack", "message", "deliver", "error"),
            subtypeDiscriminators(WireFrame.serializer().descriptor),
            "/ws/hub WireFrame discriminators — a rename/add/remove silently breaks every non-Kotlin BYOA consumer",
        )
    }

    @Test
    fun hub_enumWireStrings_arePinned() {
        assertEquals(
            setOf("unsupported_version", "forbidden", "too_large", "bad_request", "protocol", "rate_limited"),
            enumWireNames(WireErrorCode.serializer().descriptor),
            "WireErrorCode wire strings — the uniform fail-closed error vocabulary the bridge parses",
        )
        assertEquals(
            setOf("rate_limit", "tool_call", "tool_result"),
            enumWireNames(WireEventType.serializer().descriptor),
            "WireEventType wire strings — the remote self-report signal vocabulary",
        )
    }

    @Test
    fun hub_frames_renderTypeDiscriminatorInTheirBytes() {
        assertTrue(wire(WireSend("po-frontend", "hi")).contains("\"type\":\"send\""))
        assertTrue(wire(WireSubscribe(listOf("po-frontend"))).contains("\"type\":\"subscribe\""))
        assertTrue(wire(WireAck()).contains("\"type\":\"ack\""))
        assertTrue(wire(WireMessage(Message("1", "po-frontend", "frontend", "b", 1L))).contains("\"type\":\"message\""))
        assertTrue(wire(WireDeliver("x")).contains("\"type\":\"deliver\""))
        // A frame carrying an enum: the discriminator AND the enum value must both be the WIRE strings, never
        // the Kotlin constant name (a leak of `FORBIDDEN`/`TOOL_CALL` would break a case-sensitive consumer).
        val err = wire(WireError(WireErrorCode.FORBIDDEN))
        assertTrue(err.contains("\"type\":\"error\""))
        assertTrue(err.contains("\"forbidden\""))
        assertFalse(err.contains("FORBIDDEN"), "the enum must render its wire string, not the Kotlin constant name")
        val ev = wire(WireEvent(WireEventType.TOOL_CALL, tool = "Bash"))
        assertTrue(ev.contains("\"type\":\"event\""))
        assertTrue(ev.contains("\"tool_call\""))
        assertFalse(ev.contains("TOOL_CALL"))
    }

    @Test
    fun hub_envelope_wrapsFrameWithVersion_andTypeInsideFrame() {
        // The EXACT /ws/hub on-the-wire form: {"v":1,"frame":{"type":"message",...}}.
        val j = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, WireMessage(Message("1", "c", "f", "b", 1L))))
        assertTrue(j.contains("\"v\":1"), "the envelope carries its version: $j")
        assertTrue(j.contains("\"frame\":{\"type\":\"message\""), "the discriminator sits INSIDE the frame object: $j")
    }

    // ---------------- /ws/comm + /ws/events — consolidated sibling pins ----------------

    @Test
    fun comm_and_events_discriminators_arePinned() {
        assertEquals(
            setOf("message", "acl", "channels", "readState"),
            subtypeDiscriminators(CommWsServerEvent.serializer().descriptor),
            "/ws/comm server-event discriminators (the frontend live wire)",
        )
        assertEquals(
            setOf("subscribe"),
            subtypeDiscriminators(CommWsClientEvent.serializer().descriptor),
            "/ws/comm client-event discriminators",
        )
        assertEquals(
            setOf("event", "caughtup"),
            subtypeDiscriminators(EventsWsServerEvent.serializer().descriptor),
            "/ws/events server-event discriminators",
        )
        assertEquals(
            setOf("subscribe"),
            subtypeDiscriminators(EventsWsClientEvent.serializer().descriptor),
            "/ws/events client-event discriminators",
        )
    }
}
