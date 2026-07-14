package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.operator.DevicePoP
import com.tneff.cyppieagents.net.hub.operator.OperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.PerTunnelPopOutcome
import com.tneff.cyppieagents.net.hub.operator.PopResult
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvCachingPerTunnelPoPProvider
import com.tneff.cyppieagents.net.hub.operator.UvFailReason
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
import com.tneff.cyppieagents.transport.Rr3Config
import com.tneff.cyppieagents.transport.Rr3TunnelGate
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import com.tneff.cyppieagents.transport.ServerRelayChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-536/537 (M2 Option A) — the **joint 2-tunnel (N-tunnel) auth e2e**, the milestone proof that Dev's REAL client
 * PoP path (WS3 `UvCachingPerTunnelPoPProvider` + the F⑥-1 shared `CachingUserVerification`) authenticates N tunnels
 * against Backend's REAL server gate (`Rr3TunnelGate` + `OperatorAssertionVerifier`) over **REAL Noise tunnels** (real
 * NK crypto → real, distinct per-tunnel handshake hashes `h_i`). This is the real-tunnel graduation of Dev's
 * client-side `Cyp537PoolUvReuseTest` (which pins the UV-reuse contract against fakes) — here every `h_i` is a real
 * handshake hash and every PoP is verified by the real server.
 *
 * Proves the three PO F⑥ properties end-to-end:
 *  1. **1 UV for N** — the operator is prompted ONCE; the N per-tunnel PoPs reuse the cached UV (0 extra prompts) and
 *     ALL N server-authorize (each PoP verifies against its own `h_i`).
 *  2. **cross-tunnel anti-replay** — a PoP built for tunnel-k replayed onto tunnel-j (a different live `h`) is refused
 *     (`bad_signature` — the challenge is recomputed from the live `h_j`); the SAME nonce replayed on the shared gate
 *     is refused (`nonce_replayed` — one gate ⇒ one nonce ledger across all N tunnels, Backend's WS1 invariant).
 *  3. **bounded UV reuse (O5)** — the cache window is enforced by a MONOTONIC `nowMs`; past the window the next tunnel
 *     re-prompts (a stale grant can never silently satisfy a later tunnel).
 */
class Cyp536JointNTunnelAuthE2eTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.asReversed().forEach { runCatching { it() } }; scope.cancel() }

    // ── server-side fixtures (Backend WS1 — Cyp459Rr3GateTest pattern) ──
    private val hubId = "hub_joint"
    private val operatorId = "op-joint"
    private val issuer = "cp-issuer"
    private val kid = "kid1"
    private val gateNowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519()      // the CP signing keypair
    private val device = RawKeys.generateEd25519()  // the ONE operator device keypair (enrolled server-side, held client-side)
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)
    private val deviceStore = InMemoryOperatorDeviceStore().apply {
        save(EnrolledOperatorDevice("dev-joint", DeviceKeyAlg.ED25519, device.publicRaw))
    }
    private fun rr3Config() = Rr3Config(
        hubId = hubId,
        pinnedOperatorId = operatorId,
        expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) cp.publicRaw else null },
        expectedRpId = "hub.example",
    )
    /** ONE gate for the whole session ⇒ ONE shared nonce ledger across all N tunnels (Backend's WS1 revocation/nonce invariant). */
    private fun newGate() = Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), deviceStore, rr3Config(), now = { gateNowMs })

    private fun cpJwtFor(h: ByteArray) =
        minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), gateNowMs, ttlMs = 300_000)

    // ── client-side fixtures (Dev WS3 + F⑥-1) — a UV spy + the REAL UvCachingPerTunnelPoPProvider ──

    /** Counts the REAL user-verification prompts (the raw delegate under the F⑥-1 cache). */
    private class CountingUv(private val outcome: UvOutcome) : UserVerification {
        val prompts = AtomicInteger()
        override suspend fun verify(reason: UvReason): UvOutcome { prompts.incrementAndGet(); return outcome }
    }

    /** A device-key store signing with the enrolled Ed25519 key behind the injected (caching) UV — the Raw MVP path. */
    private class TestDeviceKeyStore(private val devicePrivRaw: ByteArray, private val devicePubRaw: ByteArray, private val uv: UserVerification) : OperatorDeviceKeyStore {
        override fun isEnrolled(): Boolean = true
        override fun devicePublicKey(): ByteArray = devicePubRaw
        override suspend fun sign(challenge: ByteArray): PopResult = when (val o = uv.verify(UvReason.OPERATOR_AUTH)) {
            UvOutcome.Verified -> PopResult.Signed(DevicePoP.Raw(RawKeys.ed25519Sign(devicePrivRaw, challenge)))
            is UvOutcome.Denied -> PopResult.UvFailed(o.reason)
            UvOutcome.Unavailable -> PopResult.UvFailed(UvFailReason.LOCKED_OUT)
        }
    }

    private fun provider(rawUv: CountingUv, nowMs: () -> Long, windowMs: Long = 300_000L) =
        UvCachingPerTunnelPoPProvider.wrap(
            rawUv = rawUv,
            reuseWindowMs = windowMs,
            nowMs = nowMs,
            storeFor = { uv -> TestDeviceKeyStore(device.privateRaw, device.publicRaw, uv) },
        )

    // ── real Noise tunnel factory (client leg + server leg share one live handshake hash) ──

    private class RealTunnelPair(val client: NoiseTunnel, val server: ServerNoiseTunnel)

    private suspend fun realTunnel(): RealTunnelPair {
        val dh = RawKeys.generateX25519()
        val (clientRelay, hubEnd) = InMemDuplex.pair()
        val serverDeferred = scope.async { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubEnd) }
        val client = NoiseJavaClientTransport().connect(dh.publicRaw, clientRelay)
        val server = serverDeferred.await()
        cleanups += {
            runCatching { runBlocking { client.close() } }
            runCatching { runBlocking { server.close() } }
            runCatching { runBlocking { hubEnd.close() } }
        }
        return RealTunnelPair(client, server)
    }

    /** Client builds tunnel [i]'s PoP (over its own live `h`) + assembles the wire request (the WS2 role). */
    private suspend fun buildRequest(
        provider: UvCachingPerTunnelPoPProvider,
        h: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val challenge = operatorAuthChallenge(h, hubId, nonce)
        val pop = provider.popFor("rvid", challenge)
        assertTrue(pop is PerTunnelPopOutcome.Ready, "client produced a Raw PoP for this tunnel (got $pop)")
        val sig = (pop as PerTunnelPopOutcome.Ready).pop
        return CommJson.encodeToString(TunnelAuthRequest(cpJwtFor(h), OperatorPoPWire.Raw(sig), nonce)).encodeToByteArray()
    }

    /** Send [reqBytes] over the client tunnel and run the server gate over the paired server tunnel; return authorize. */
    private suspend fun authOverTunnel(gate: Rr3TunnelGate, pair: RealTunnelPair, reqBytes: ByteArray): Boolean {
        val serverAuth = scope.async { gate.authorize(pair.server) }
        pair.client.send(reqBytes)
        return withTimeout(15_000) { serverAuth.await() }
    }

    private fun nonce(vararg b: Byte) = b

    @Test
    fun oneUvForN_realTunnels_allAuthorize() = runBlocking {
        val rawUv = CountingUv(UvOutcome.Verified)
        val provider = provider(rawUv, nowMs = { 1_000L }) // fixed clock ⇒ all within the reuse window
        val gate = newGate()

        // Open the session UV ONCE, then dial N real tunnels — each PoP over its OWN live h, each server-authorized.
        assertTrue(provider.openSession() != null, "the one session UV opens (Verified)")
        val n = 3
        val hashes = mutableSetOf<String>()
        repeat(n) { i ->
            val pair = realTunnel()
            hashes += pair.client.handshakeHash.joinToString("") { "%02x".format(it) }
            assertEquals(pair.client.handshakeHash.toList(), pair.server.handshakeHash.toList(), "client/server share the live h")
            val req = buildRequest(provider, pair.client.handshakeHash, nonce(1, 2, i.toByte()))
            assertTrue(authOverTunnel(gate, pair, req), "tunnel #$i authorizes (PoP verifies against its own live h_$i)")
        }
        assertEquals(n, hashes.size, "each real tunnel produced a DISTINCT live handshake hash (N independent tunnels)")
        assertEquals(1, rawUv.prompts.get(), "★ 1 UV for N: N tunnels authenticated with exactly ONE real UV prompt (F⑥-1)")
    }

    @Test
    fun crossTunnelReplay_isRejected_byLiveHAndNonceLedger() = runBlocking {
        val rawUv = CountingUv(UvOutcome.Verified)
        val provider = provider(rawUv, nowMs = { 1_000L })
        val gate = newGate() // ONE gate ⇒ ONE nonce ledger across tunnels

        // Tunnel K: a valid request (its PoP is bound to h_K + nonce_K).
        val tunnelK = realTunnel()
        val nonceK = nonce(7, 7, 1)
        val reqK = buildRequest(provider, tunnelK.client.handshakeHash, nonceK)
        assertTrue(authOverTunnel(gate, tunnelK, reqK), "control: tunnel K's own PoP authorizes on tunnel K")

        // ★ (a) cross-tunnel h replay: send tunnel-K's EXACT request bytes over tunnel J (a different live h_J).
        // The gate recomputes the challenge from h_J → the K-signed PoP no longer verifies → bad_signature (rejected).
        val tunnelJ = realTunnel()
        assertTrue(
            tunnelJ.server.handshakeHash.toList() != tunnelK.server.handshakeHash.toList(),
            "precondition: tunnel J has a DIFFERENT live handshake hash than tunnel K",
        )
        assertFalse(
            authOverTunnel(newGate(), tunnelJ, reqK),
            "★ a PoP built for tunnel K is refused on tunnel J (its live h_J recomputes a different challenge → bad_signature)",
        )

        // ★ (b) nonce replay on the SHARED ledger: a FRESH, correctly-h-bound request that REUSES nonce_K → nonce_replayed.
        // (Re-sign for tunnel K's h with the same nonce so the signature is valid; only the single-use nonce fails.)
        val tunnelK2 = realTunnel()
        val reqReplayNonce = buildRequest(provider, tunnelK2.client.handshakeHash, nonceK) // valid crypto, but nonce_K reused
        // First consume nonce_K on THIS gate via a legit use, then replay it.
        val freshGate = newGate()
        val tunnelA = realTunnel()
        assertTrue(authOverTunnel(freshGate, tunnelA, buildRequest(provider, tunnelA.client.handshakeHash, nonceK)), "first use of nonce_K authorizes")
        assertFalse(
            authOverTunnel(freshGate, tunnelK2, reqReplayNonce),
            "★ the SAME nonce replayed on the shared gate is refused (nonce_replayed — one ledger across all N tunnels)",
        )
    }

    @Test
    fun boundedUvReuse_monotonic_repromptsPastWindow() = runBlocking {
        // O5: the reuse window is bounded by a MONOTONIC nowMs — past the window the next tunnel re-prompts (a stale
        // grant never silently satisfies a later tunnel). Proven over the real popFor path.
        val rawUv = CountingUv(UvOutcome.Verified)
        var clock = 1_000L
        val provider = provider(rawUv, nowMs = { clock }, windowMs = 60_000L)
        val gate = newGate()

        val t1 = realTunnel()
        assertTrue(authOverTunnel(gate, t1, buildRequest(provider, t1.client.handshakeHash, nonce(1))), "tunnel 1 authorizes (prompt #1)")
        assertEquals(1, rawUv.prompts.get(), "one prompt so far")

        clock += 60_001L // advance the MONOTONIC clock past the bounded reuse window
        val t2 = realTunnel()
        assertTrue(authOverTunnel(gate, t2, buildRequest(provider, t2.client.handshakeHash, nonce(2))), "tunnel 2 authorizes (re-prompted)")
        assertEquals(2, rawUv.prompts.get(), "★ past the bounded window the next tunnel RE-PROMPTS — reuse is bounded, never a stale grant")
    }

    // ── in-memory duplex relay (both Noise legs) — mirrors the CypM2TierBTransportTest harness ──
    private class InMemDuplex private constructor(
        private val outbound: Channel<ByteArray>,
        private val inbound: Channel<ByteArray>,
    ) : RelayChannel, ServerRelayChannel {
        override suspend fun send(frame: ByteArray) { outbound.send(frame.copyOf()) }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { outbound.close() }
        companion object {
            fun pair(): Pair<RelayChannel, ServerRelayChannel> {
                val c2h = Channel<ByteArray>(Channel.UNLIMITED)
                val h2c = Channel<ByteArray>(Channel.UNLIMITED)
                return InMemDuplex(outbound = c2h, inbound = h2c) to InMemDuplex(outbound = h2c, inbound = c2h)
            }
        }
    }
}
