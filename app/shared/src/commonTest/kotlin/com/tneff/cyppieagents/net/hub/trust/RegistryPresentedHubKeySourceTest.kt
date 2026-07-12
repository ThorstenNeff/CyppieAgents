package com.tneff.cyppieagents.net.hub.trust

import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.connect.StubControlPlaneClient
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * CYP-495 — the registry-backed presented-key source decodes a hub's `dhPubKey` straight-through to the raw
 * 32-byte Noise static, and **fails closed** (→ `null`) on a missing / non-base64 / wrong-sized value so
 * [TofuHubTrust] never handshakes blind. `of` binds one hub; `fromControlPlane` resolves via the registry.
 */
class RegistryPresentedHubKeySourceTest {

    private val key32 = ByteArray(HUB_STATIC_KEY_SIZE) { it.toByte() }
    private fun hub(id: String, dh: String) =
        HubDescriptor(id, name = "n", online = true, defaultPort = 8787, lastSeen = 1L, dhPubKey = dh)

    @Test
    fun of_decodesDhPubKey_toRawStatic() = runTest {
        val src = RegistryPresentedHubKeySource.of(hub("hub-1", Base64.Default.encode(key32)))
        assertContentEquals(key32, src.presentedStatic("hub-1"))
    }

    @Test
    fun of_matchesOnlyTheRequestedHubId() = runTest {
        val src = RegistryPresentedHubKeySource.of(hub("hub-1", Base64.Default.encode(key32)))
        assertNull(src.presentedStatic("hub-2"))
    }

    @Test
    fun failsClosed_onEmptyMalformedOrWrongSize() = runTest {
        assertNull(RegistryPresentedHubKeySource.of(hub("h", "")).presentedStatic("h"))
        assertNull(RegistryPresentedHubKeySource.of(hub("h", "!!! not base64 !!!")).presentedStatic("h"))
        assertNull(
            RegistryPresentedHubKeySource.of(hub("h", Base64.Default.encode(ByteArray(16)))).presentedStatic("h"),
            "a wrong-sized decoded key must fail closed (never a partial/blind static)",
        )
    }

    @Test
    fun fromControlPlane_resolvesViaRegistry() = runTest {
        val cp = StubControlPlaneClient(hubs = listOf(hub("hub-1", Base64.Default.encode(key32))))
        assertContentEquals(key32, RegistryPresentedHubKeySource.fromControlPlane(cp).presentedStatic("hub-1"))
        assertNull(RegistryPresentedHubKeySource.fromControlPlane(cp).presentedStatic("absent"))
    }
}
