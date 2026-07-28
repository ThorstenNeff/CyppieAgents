package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FEDERATION_PEER_PURPOSE
import com.tneff.cyppieagents.model.FederationFrame
import com.tneff.cyppieagents.model.FederationHello
import com.tneff.cyppieagents.model.negotiateVersion
import com.tneff.cyppieagents.operator.OPERATOR_AUTH_PURPOSE
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-859 (S-Fed-1①) — teeth for the frozen §4b federation wire foundation. Block bodies (not expression bodies)
 * so an `assertFailsWith` (which returns the exception) can never make a @Test method non-void.
 */
@OptIn(ExperimentalFederation::class)
class Cyp859FederationWireFoundationTest {

    // --- ① roundtrip (polymorphic via CommJson type-discriminator) ----------------------------------------------

    @Test
    fun federationHello_roundTripsWithTypeDiscriminator() {
        val hello: FederationFrame = FederationHello(protoMin = 1, protoMax = 3)
        val json = CommJson.encodeToString(FederationFrame.serializer(), hello)
        assertTrue("\"type\":\"hello\"" in json, "encoded frame must carry the CommJson type-discriminator: $json")
        val back = CommJson.decodeFromString(FederationFrame.serializer(), json)
        assertEquals(hello, back)
    }

    // --- ② negotiate-down ---------------------------------------------------------------------------------------

    @Test
    fun negotiate_picksHighestCommonVersion_negotiatesDown() {
        // overlap [2..3] → highest common = 3
        assertEquals(3, negotiateVersion(FederationHello(1, 3), FederationHello(2, 5)))
        // local caps the negotiation DOWN to 2 even though remote speaks up to 5
        assertEquals(2, negotiateVersion(FederationHello(1, 2), FederationHello(1, 5)))
        // identical single-version ranges
        assertEquals(4, negotiateVersion(FederationHello(4, 4), FederationHello(4, 4)))
    }

    // --- ③ out-of-range = refuse (fail-closed) ------------------------------------------------------------------

    @Test
    fun negotiate_disjointRanges_refuses() {
        assertNull(negotiateVersion(FederationHello(1, 2), FederationHello(4, 5)), "disjoint (local below) → refuse")
        assertNull(negotiateVersion(FederationHello(6, 7), FederationHello(1, 3)), "disjoint (local above) → refuse")
        // a malformed inverted remote range must also refuse, never coincidentally admit
        assertNull(negotiateVersion(FederationHello(1, 5), FederationHello(4, 2)), "inverted remote range → refuse")
    }

    // --- ④ unknown frame type = fail-closed ---------------------------------------------------------------------

    @Test
    fun unknownFrameType_failsClosed() {
        // an unmapped discriminator must THROW on decode — the peer closes, never silently produces a frame.
        assertFailsWith<SerializationException> {
            CommJson.decodeFromString(FederationFrame.serializer(), """{"type":"bogus-not-a-frame"}""")
        }
    }

    // --- forward-compat (additive) ------------------------------------------------------------------------------

    @Test
    fun forwardCompat_toleratesUnknownAdditiveField() {
        // a newer peer adds an additive field; an older decoder IGNORES it (CommJson ignoreUnknownKeys) → still decodes.
        val json = """{"type":"hello","protoMin":1,"protoMax":3,"futureAdditiveField":99}"""
        val back = CommJson.decodeFromString(FederationFrame.serializer(), json)
        assertEquals(FederationHello(1, 3), back)
    }

    // --- §4b-1 PoP tag domain separation ------------------------------------------------------------------------

    @Test
    fun federationPeerPurpose_isDomainSeparatedFromOperator() {
        assertEquals("federation-peer", FEDERATION_PEER_PURPOSE)
        assertTrue(
            FEDERATION_PEER_PURPOSE != OPERATOR_AUTH_PURPOSE,
            "the federation PoP purpose MUST differ from the operator PoP purpose (domain separation, §4b-1)",
        )
    }
}
