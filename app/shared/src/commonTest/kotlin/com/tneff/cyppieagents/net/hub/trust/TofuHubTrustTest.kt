package com.tneff.cyppieagents.net.hub.trust

import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-478 — the TOFU decision honesty teeth (Entscheidung 3 = Option X). These pin the security-critical
 * behaviour of [TofuHubTrust].resolve:
 *  - first use adopts **only after** an OOB confirm, and a **reject never pins** (poisoned-first-pin defence);
 *  - a matching pin handshakes against the pinned key (no re-prompt);
 *  - a **changed** key is a hard block ([TrustResolution.Changed] with the *pinned* fingerprint) that **never**
 *    overwrites the pin (CI-5, terminal, OOB-only re-pin);
 *  - a missing / wrong-sized presented key fails closed (never a blind handshake).
 */
class TofuHubTrustTest {

    private val hubId = "hub-1"
    private fun keyA() = ByteArray(HUB_STATIC_KEY_SIZE) { it.toByte() }
    private fun keyB() = ByteArray(HUB_STATIC_KEY_SIZE) { (it + 1).toByte() }

    private val autoApprove = OobFingerprintConfirmer { _, _ -> }
    private val reject = OobFingerprintConfirmer { h, _ -> throw TrustConfirmationRejectedException(h) }
    private val neverPrompt = OobFingerprintConfirmer { _, _ -> fail("must not prompt for OOB confirm here") }

    @Test
    fun firstUse_afterConfirm_adoptsAndReturnsFirstUse() = runTest {
        val store = InMemoryPinnedHubStore()
        val trust = TofuHubTrust(MapPresentedHubKeySource(mapOf(hubId to keyA())), store, autoApprove)

        val r = trust.resolve(hubId)
        assertTrue(r is TrustResolution.FirstUse)
        assertContentEquals(keyA(), r.hubStatic)
        assertEquals(HubKeyFingerprint.of(keyA()), r.fingerprint)
        assertContentEquals(keyA(), store.pinnedKey(hubId), "the key must be pinned after a confirmed first use")
    }

    @Test
    fun firstUse_reject_doesNotAdopt() = runTest {
        val store = InMemoryPinnedHubStore()
        val trust = TofuHubTrust(MapPresentedHubKeySource(mapOf(hubId to keyA())), store, reject)

        assertFailsWith<TrustConfirmationRejectedException> { trust.resolve(hubId) }
        assertNull(store.pinnedKey(hubId), "a rejected first-use fingerprint must NEVER be pinned (fail-closed)")
    }

    @Test
    fun pinnedMatch_returnsPinned_withoutPrompting() = runTest {
        val store = InMemoryPinnedHubStore().apply { pin(hubId, keyA()) }
        val trust = TofuHubTrust(MapPresentedHubKeySource(mapOf(hubId to keyA())), store, neverPrompt)

        val r = trust.resolve(hubId)
        assertTrue(r is TrustResolution.Pinned)
        assertContentEquals(keyA(), r.hubStatic)
    }

    @Test
    fun changedKey_isHardBlock_withPinnedFingerprint_neverReAdopts() = runTest {
        val store = InMemoryPinnedHubStore().apply { pin(hubId, keyA()) }
        val trust = TofuHubTrust(MapPresentedHubKeySource(mapOf(hubId to keyB())), store, neverPrompt)

        val r = trust.resolve(hubId)
        assertTrue(r is TrustResolution.Changed)
        assertEquals(HubKeyFingerprint.of(keyA()), r.expectedFingerprint, "expected fp = the PINNED key (CI-5)")
        assertContentEquals(keyA(), store.pinnedKey(hubId), "a changed key must NOT silently overwrite the pin")
    }

    @Test
    fun missingPresentedKey_failsClosed() = runTest {
        val trust = TofuHubTrust(MapPresentedHubKeySource(emptyMap()), InMemoryPinnedHubStore(), autoApprove)
        assertFailsWith<HubStaticUnavailableException> { trust.resolve(hubId) }
    }

    @Test
    fun wrongSizedPresentedKey_failsClosed_withoutPromptingOrPinning() = runTest {
        val store = InMemoryPinnedHubStore()
        val trust = TofuHubTrust(MapPresentedHubKeySource(mapOf(hubId to ByteArray(16))), store, neverPrompt)
        assertFailsWith<HubStaticUnavailableException> { trust.resolve(hubId) }
        assertNull(store.pinnedKey(hubId))
    }
}
