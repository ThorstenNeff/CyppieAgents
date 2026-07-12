package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.HubDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-481 — the `:core` [HubDescriptor] wire DTO round-trips through the shared [CommJson] on **every** target
 * (commonTest → jvm/js/wasmJs/android), so the desktop client (Kotlin) and the regenerated TS type (web, Team-2)
 * decode the same bytes. The load-bearing tooth: **[HubDescriptor.dhPubKey] survives the wire verbatim** — it is the
 * value the client TOFU-pins (CYP-478).
 */
class Cyp481HubDescriptorTest {

    private inline fun <reified T> roundTrip(value: T): T =
        CommJson.decodeFromString<T>(CommJson.encodeToString(value))

    @Test
    fun hubDescriptorRoundTrips_allFields_inclDhPubKey() {
        val d = HubDescriptor(
            hubId = "hub_0011223344556677",
            name = "My Hub",
            online = true,
            defaultPort = 8787,
            lastSeen = 1_782_517_200_000,
            dhPubKey = "Zm9vYmFyMzJieXRlc2Jhc2U2NHJhd3gyNTUxOXB1Yg",
        )
        assertEquals(d, roundTrip(d), "the wire DTO round-trips losslessly on every target")
        // ★ the load-bearing field: the client pins THIS exact value, so it must survive the wire byte-for-byte.
        assertEquals(d.dhPubKey, roundTrip(d).dhPubKey)
        assertTrue(CommJson.encodeToString(d).contains("dhPubKey"), "dhPubKey is present on the wire (TS regen sees it)")
    }

    @Test
    fun toleratesUnknownFutureKeys() {
        // Additive contract (CYP-234 drift-gates): a future field must not break existing consumers.
        val json =
            """{"hubId":"h","name":"n","online":false,"defaultPort":1,"lastSeen":0,"dhPubKey":"k","futureField":7}"""
        assertEquals("k", CommJson.decodeFromString<HubDescriptor>(json).dhPubKey)
    }
}
