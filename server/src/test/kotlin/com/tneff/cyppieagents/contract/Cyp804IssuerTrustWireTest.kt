package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.HubDescriptor
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-804 (axis c) — the explicitNulls WIRE tooth for [HubDescriptor.issuerTrust]. po2's web-ts models the field as
 * a zod `.optional()` on a VALIDATED root (`GET /api/cp/hubs`): ABSENT is accepted, explicit `null` is NOT — an
 * explicit `null` would zod-FAIL the whole hub list. This pins that the shared [CommJson] (`explicitNulls = false`)
 * OMITS the field when null (absent-when-unknown) and EMITS it as a plain string when set. Mutation (a config or a
 * field change that made `issuerTrust=null` serialize as `"issuerTrust":null`) → this reds.
 */
class Cyp804IssuerTrustWireTest {

    private fun descriptor(issuerTrust: HubIssuerTrust?) = HubDescriptor(
        hubId = "h", name = "n", online = false, defaultPort = 8787, lastSeen = 0L, dhPubKey = "k", issuerTrust = issuerTrust,
    )

    @Test
    fun issuerTrust_null_isOmitted_neverExplicitNull() {
        val json = CommJson.encodeToString(HubDescriptor.serializer(), descriptor(null))
        assertFalse(
            "issuerTrust" in json,
            "issuerTrust MUST be ABSENT when null (CommJson explicitNulls=false) — an explicit null zod-fails the web-ts hub list. Got: $json",
        )
    }

    @Test
    fun issuerTrust_set_isEmittedAsPlainStringEnum() {
        val json = CommJson.encodeToString(HubDescriptor.serializer(), descriptor(HubIssuerTrust.NOT_TRUSTED))
        assertTrue(
            "\"issuerTrust\":\"NOT_TRUSTED\"" in json,
            "issuerTrust MUST serialize as a plain string enum value (no wrapper/discriminant). Got: $json",
        )
    }

    @Test
    fun issuerTrust_roundTrips_absentDecodesToNull() {
        // Symmetric: a payload WITHOUT the field decodes back to null (absent == unknown), never a fabricated value.
        val decoded = CommJson.decodeFromString(
            HubDescriptor.serializer(),
            """{"hubId":"h","name":"n","online":false,"defaultPort":8787,"lastSeen":0,"dhPubKey":"k"}""",
        )
        assertEquals(null, decoded.issuerTrust)
    }
}
