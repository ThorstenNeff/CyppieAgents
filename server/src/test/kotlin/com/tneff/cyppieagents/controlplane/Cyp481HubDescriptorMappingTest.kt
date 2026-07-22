package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.model.HubDescriptor
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-481 — [RegisteredHub.toDescriptor] projects the zero-knowledge registry entry to the client-facing
 * [HubDescriptor]. The load-bearing tooth: **[dhPubKey] flows straight through** (the client TOFU-pins exactly the
 * hub's published static — a dropped/altered key would break the Noise pin). Presence is caller-supplied (H1).
 */
class Cyp481HubDescriptorMappingTest {

    private fun hub() = RegisteredHub(
        hubId = "hub_abc",
        ownerId = "owner-1",
        name = "Prod Hub",
        defaultPort = 8787,
        signingPubKey = "c2lnbmluZ1B1Yg",
        dhPubKey = "ZGhQdWJLZXlSYXcz",
    )

    @Test
    fun toDescriptor_carriesDhPubKeyStraightThrough_andIdentityFields() {
        val d: HubDescriptor = hub().toDescriptor(online = true, lastSeen = 1_782_517_200_000)

        // ★ dhPubKey is the hub's exact published static (mutation: map a different/blank value → RED).
        assertEquals("ZGhQdWJLZXlSYXcz", d.dhPubKey)
        assertEquals(hub().dhPubKey, d.dhPubKey)
        // identity + routing copied
        assertEquals("hub_abc", d.hubId)
        assertEquals("Prod Hub", d.name)
        assertEquals(8787, d.defaultPort)
    }

    @Test
    fun toDescriptor_carriesIssuerTrustPostureThrough_absentWhenUnknown() {
        // CYP-804 ① — the stored per-hub issuer-trust posture (axis c) flows straight through to the client-facing
        // descriptor. Mutation: drop `issuerTrust = issuerTrust` in toDescriptor → the set case reds.
        assertEquals(
            HubIssuerTrust.NOT_TRUSTED,
            hub().copy(issuerTrust = HubIssuerTrust.NOT_TRUSTED).toDescriptor(online = false, lastSeen = 0).issuerTrust,
        )
        // Unknown/not-reported (default) → null (absent-when-unknown; the wire OMITS it, ②'s tooth proves that).
        assertNull(hub().toDescriptor(online = true, lastSeen = 42).issuerTrust)
    }

    @Test
    fun toDescriptor_presenceIsCallerSupplied() {
        // The registry is zero-knowledge (no presence on RegisteredHub); online/lastSeen come from the caller (H1).
        val offline = hub().toDescriptor(online = false, lastSeen = 0)
        assertEquals(false, offline.online)
        assertEquals(0, offline.lastSeen)
        val online = hub().toDescriptor(online = true, lastSeen = 42)
        assertEquals(true, online.online)
        assertEquals(42, online.lastSeen)
    }
}
