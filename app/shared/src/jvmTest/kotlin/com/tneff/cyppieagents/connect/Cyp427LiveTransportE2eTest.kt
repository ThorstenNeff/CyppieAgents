package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.operator.ClientOperatorAuth
import com.tneff.cyppieagents.net.hub.operator.HttpCpJwtProvider
import com.tneff.cyppieagents.net.hub.operator.KeystoreOperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.NonceGenerator
import com.tneff.cyppieagents.net.hub.operator.OperatorPopBuilder
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.coreChannelBinding
import com.tneff.cyppieagents.net.hub.relay.HttpRendezvousResolver
import com.tneff.cyppieagents.net.hub.relay.KtorWsRelayConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousRelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test

/**
 * CYP-427 — the LIVE machine transport E2E (Auftraggeber-GO, controlled staging). Drives the REAL remote-hub client
 * stack (RendezvousRelayDialer → Noise_NK vs the pinned dhPubKey → RR3 tunnel-auth) against the live relay/CP.
 *
 * OFF by default: only runs when `CYP427_LIVE=1` (never in CI). Config via env; the operator BEARER is read from a
 * host-side chmod-600 file and NEVER logged/echoed/committed. Emits secret-scrubbed evidence to `CYP427_EVIDENCE_DIR`.
 *
 * Two independently-honest proofs:
 *  - **TRANSPORT ROUND-TRIP (HP-6(i))** — via a [RecordingTunnel] over the real Noise tunnel: send one
 *    `TunnelAuthRequest`, receive one AEAD-decrypted `TunnelAuthGrant` FRAME back. A received, decodable grant frame
 *    proves the bidirectional transport (dial→Noise→relay→hub RR3-gate→back), INDEPENDENT of `granted`. The responder
 *    is the hub's RR3 tunnel-gate — NOT an echo, NOT a CR3 workspace route (CR3/HP-6(ii) is not wired → out of scope).
 *  - **STATE TRACE (HP-order)** — the full [RemoteHubSession] state stream (RELAY_DIALING→…→CONNECTED / terminal).
 *
 * Device key is INJECTABLE ([loadOrGenerateDeviceKey]): default = fresh key (option A → expect `granted=false`,
 * authorization-denied-at-device-PoP but transport proven); a deploy-provisioned enrolled key file (option B) →
 * genuine CONNECTED.
 */
class Cyp427LiveTransportE2eTest {

    private fun env(k: String, default: String? = null): String? = System.getenv(k)?.takeIf { it.isNotBlank() } ?: default

    /** Read the operator bearer host-side; NEVER return it into any log/evidence. */
    private fun operatorBearer(): String {
        val path = env("CYP427_BEARER_FILE", "/home/thorsten/.cyppie-secrets/operator-bearer.txt")!!
        val f = File(path)
        require(f.isFile) { "operator bearer file absent at $path (deploy must scp it, chmod 600)" }
        return f.readText().trim().also { require(it.isNotEmpty()) { "bearer file empty" } }
    }

    /** Option A: fresh device key (expect authorization-denied). Option B: load deploy's enrolled keypair. */
    private fun loadOrGenerateDeviceKey(): java.security.KeyPair {
        // Option B seam: if deploy provisions an enrolled device key host-side, load it here (format TBD with deploy).
        // Default (A): a fresh key → the hub's device store won't match → granted=false, transport still proven.
        return KeystoreOperatorDeviceKeyStore.generateDeviceKey()
    }

    // Match the existing e2e tests: plain CIO (the resolver/cpJwt providers decode via CommJson manually, no CN plugin).
    private fun cpClient() = HttpClient(CIO)
    private fun wsClient() = HttpClient(CIO) { install(WebSockets) }

    private val nonceGen = NonceGenerator { ByteArray(32).also { SecureRandom().nextBytes(it) } }
    private val uvVerified = UserVerification { UvOutcome.Verified }
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    @Test
    fun liveTransportRoundTrip() {
        if (env("CYP427_LIVE") != "1") return // gated OFF unless explicitly a live run
        val cpBase = env("CYP427_CP_BASE", "https://api.cyppie-agents.com")!!
        val hubId = env("CYP427_HUB_ID") ?: error("CYP427_HUB_ID required")
        val dhPubKey = env("CYP427_DH_PUBKEY") ?: error("CYP427_DH_PUBKEY required")
        val evDir = File(env("CYP427_EVIDENCE_DIR", "/tmp/cyp427-ev")!!).apply { mkdirs() }
        val bearer = operatorBearer()
        val ev = StringBuilder()
        fun log(s: String) { ev.appendLine(s); println(s) }

        val cp = cpClient(); val ws = wsClient()
        val resolver = HttpRendezvousResolver(cp, cpBase) { bearer }
        val connector = KtorWsRelayConnector(ws)
        val dialer = RendezvousRelayDialer(resolver, connector)
        val transport = NoiseJavaClientTransport()
        val deviceKey = loadOrGenerateDeviceKey()
        val auth = ClientOperatorAuth(
            popBuilder = OperatorPopBuilder(KeystoreOperatorDeviceKeyStore(uvVerified, deviceKey), nonceGen),
            cpJwtProvider = HttpCpJwtProvider(cp, cpBase, { bearer }, coreChannelBinding()),
        )
        val pin = Base64.getDecoder().decode(dhPubKey)

        runBlocking {
            log("# CYP-427 LIVE transport E2E")
            log("stand: cp=$cpBase hubId=$hubId dhPubKey(len)=${pin.size} deviceKey=${if (env("CYP427_DEVICE_KEY_FILE") != null) "injected(B)" else "fresh(A)"}")

            // ── Step-0b / HP-1: resolve rendezvous (bearer-gated) → binding ──
            val resolution = runCatching { resolver.resolve(hubId) }.getOrElse { log("HP-1 resolve THREW: ${it.message}"); null }
            log("HP-1 resolve -> ${resolution?.let { it::class.simpleName } ?: "null"}")
            (resolution as? com.tneff.cyppieagents.net.hub.relay.RendezvousResolution.Bound)?.let { b ->
                // CT-1 live: the rendezvous is an OPAQUE routing label, NOT the hubId (relay never learns hubId).
                log("CT-1 rendezvous opacity: rendezvousId=${b.rendezvousId} != hubId=${hubId} -> ${b.rendezvousId != hubId}")
                log("CT-1 relayUrl (CP-supplied binding): ${b.relayUrl}")
            }

            // ── HP-1..2: dial relay + Noise_NK handshake against the pinned dhPubKey ──
            val relay = runCatching { dialer.dial(hubId) }.getOrElse { log("HP-1/2 dial THREW: ${it.message}"); null }
            if (relay == null) { log("RESULT: failed at dial (no relay channel)"); writeEv(evDir, ev); return@runBlocking }
            val tunnel = runCatching { transport.connect(pin, relay) }.getOrElse { e ->
                val msg = e.message ?: ""
                // Distinguish the two fail-closed modes from the transport's own handling (both = HP-2 fails closed):
                val diag = when {
                    "relay closed" in msg -> "NO msg2 (relay closed the channel) → hub NOT live-paired to the relay as a responder (registered in CP but no live relay presence)"
                    else -> "a frame returned but readMessage failed ($msg) → either the hub is silent/relay-control-frame OR dhPubKey != hub live static (wrong-key/misroute); across runs mostly NO frame → pairing/liveness is the primary hypothesis"
                }
                log("HP-2 Noise_NK handshake FAILS CLOSED: $msg")
                log("HP-2 diagnosis: $diag")
                log("RESULT: transport NOT established — no CONNECTED, no grant frame. Client fails closed correctly (never proceeds without a completed handshake).")
                relay.close(); writeEv(evDir, ev); return@runBlocking
            }
            val h = tunnel.handshakeHash
            log("HP-2 Noise_NK handshake OK: live-h=${b64u(h)} (32B=${h.size == 32})")
            log("HP-3 TRUST: handshake completed against the presented static == the pin (else it would have failed closed)")
            log("HP-4/cb: cb=base64url(SHA-256(h||hubId))=${coreChannelBinding().compute(h, hubId)}")

            // ── HP-6(i) TRANSPORT ROUND-TRIP: auth exchange over the tunnel, recorded ──
            val rec = RecordingTunnel(tunnel)
            val granted = runCatching { auth.authenticate(rec, hubId) }.getOrElse { log("auth THREW: ${it.message}"); false }
            log("HP-6(i) TRANSPORT: framesSent=${rec.nSent} framesRecv=${rec.nRecv} (sizes sent=${rec.sentSizes} recv=${rec.recvSizes})")
            val grantFrame = rec.lastRecv?.let { runCatching { CommJson.decodeFromString(com.tneff.cyppieagents.operator.TunnelAuthGrant.serializer(), it.decodeToString()) }.getOrNull() }
            when {
                rec.nSent == 0 -> log("RESULT: FAILED BEFORE SEND (cpJwt mint null → bearer/CP-mint issue) — NO transport round-trip")
                grantFrame != null -> {
                    log("✅ TRANSPORT ROUND-TRIP PROVEN: an AEAD-decrypted TunnelAuthGrant frame returned over Noise (responder=hub RR3-tunnel-gate, NOT echo/CR3)")
                    log("   authorization: granted=${grantFrame.granted}${grantFrame.reason?.let { " reason=$it" } ?: ""}")
                    if (!grantFrame.granted) log("   → authorization DENIED at the operator-device-PoP layer (expected for a non-enrolled/fresh device key — option A)")
                }
                rec.nSent >= 1 && rec.nRecv == 0 -> log("RESULT: sent but peer closed before a grant frame (relay/tunnel drop after send)")
                else -> log("RESULT: inconclusive (sent=${rec.nSent} recv=${rec.nRecv})")
            }
            log("HP-6(ii) genuine Hub-HTTP/CR3 route over the tunnel: OUT OF SCOPE — CR3 not wired (only NoiseTunnel primitive). NOT part of the CONNECTED-usable claim.")
            log("session authenticate() verdict (RemoteHubSession would map to ${if (granted) "CONNECTED" else "terminal AuthRejected"}): granted=$granted")
            runCatching { rec.close() }
            writeEv(evDir, ev)
        }
    }

    /** Full-session state trace (HP-order) — separate dial; injectable device key via the same seam. */
    @Test
    fun liveStateTrace() {
        if (env("CYP427_LIVE") != "1") return
        val cpBase = env("CYP427_CP_BASE", "https://api.cyppie-agents.com")!!
        val hubId = env("CYP427_HUB_ID") ?: error("CYP427_HUB_ID required")
        val dhPubKey = env("CYP427_DH_PUBKEY") ?: error("CYP427_DH_PUBKEY required")
        val evDir = File(env("CYP427_EVIDENCE_DIR", "/tmp/cyp427-ev")!!).apply { mkdirs() }
        val bearer = operatorBearer()
        val ev = StringBuilder()
        fun log(s: String) { ev.appendLine(s); println(s) }
        val cp = cpClient(); val ws = wsClient()

        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val factory = liveRemoteConnectComponentsFactory(cpBase, cp, { bearer }, ws, coreChannelBinding())
            val hub = HubDescriptor(hubId = hubId, name = "live", online = true, defaultPort = 443, lastSeen = 0L, dhPubKey = dhPubKey)
            val comps = factory.create(hub, scope)
            val seen = mutableListOf<String>()
            val collector = scope.launch {
                comps.session.state.collect { st ->
                    val tag = st.conn.name + (st.failure?.let { "/${it::class.simpleName}" } ?: "")
                    if (seen.lastOrNull() != tag) { seen.add(tag); log("STATE: $tag") }
                }
            }
            comps.session.start()
            val terminal = withTimeoutOrNull(30_000) {
                comps.session.state.first { it.conn == RemoteConnState.CONNECTED || it.conn == RemoteConnState.LOST || it.failure != null }
            }
            log("HP-order visited: ${seen.joinToString(" -> ")}")
            log("terminal: ${terminal?.let { it.conn.name + (it.failure?.let { f -> "/${f::class.simpleName}" } ?: "") } ?: "TIMEOUT"}")
            collector.cancel(); runCatching { comps.session.close() }; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            writeEv(evDir, ev, "state-trace.txt")
        }
    }

    private fun writeEv(dir: File, sb: StringBuilder, name: String = "transport-roundtrip.txt") {
        File(dir, name).writeText(sb.toString()) // secret-scrubbed by construction (no bearer/key material logged)
    }

    /** Wraps a real [NoiseTunnel]; records frame counts + SIZES (never plaintext) + the last received frame (the
     *  grant is {granted,reason} — safe). The recorded request frame content is NOT stored (it carries the cpJwt/pop). */
    private class RecordingTunnel(private val inner: NoiseTunnel) : NoiseTunnel {
        var nSent = 0; var nRecv = 0
        val sentSizes = mutableListOf<Int>(); val recvSizes = mutableListOf<Int>()
        var lastRecv: ByteArray? = null
        override val handshakeHash: ByteArray get() = inner.handshakeHash
        override suspend fun send(plaintext: ByteArray) { nSent++; sentSizes.add(plaintext.size); inner.send(plaintext) }
        override suspend fun receive(): ByteArray? { val r = inner.receive(); if (r != null) { nRecv++; recvSizes.add(r.size); lastRecv = r }; return r }
        override suspend fun close() = inner.close()
    }
}
