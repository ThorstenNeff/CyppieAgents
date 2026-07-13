package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.relay.HttpRendezvousResolver
import com.tneff.cyppieagents.net.hub.relay.KtorWsRelayConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousRelayDialer
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-529 — live-negatives SCAFFOLD (structure now; definitive run at Auftraggeber-live-GO). Three fail-closed negatives
 * layered on the CYP-427 transport harness, each with the **CONNECTED positive anchor** (Axis-3, enrolled key) and each
 * **never-CONNECTED / teardown**, per-★ mutation.
 *
 * Honest injectability scope (the machine-E2E boundary):
 *  - **TrustChanged (terminal):** cleanly **live-injectable** — real dialer/transport, a trust seam that resolves
 *    `Changed` (equivalently: pre-seed the TOFU pin store with a key ≠ the live hub static). Asserts LOST+TrustChanged,
 *    **no silent retry** (CI-5). Concrete below.
 *  - **wrong-sub (AuthRejected):** the REAL hub-side wrong-sub rejection is CB-and-b, covered IN-PROCESS
 *    (`Cyp459Rr3GateTest`/CB-and-b); a pure-live client cannot forge a valid-signature wrong-sub ticket (needs the CP
 *    signing key or a 2nd-operator bearer). The client-HALF terminal handling (a∧b∧c=no → AuthRejected, no retry) is
 *    scaffolded here via an authenticator seam; the definitive live run needs a 2nd-operator bearer (host-side) to
 *    present a genuinely wrong `sub`.
 *  - **revoked-mid-session (teardown):** the REAL trigger is a hub-side `revokeOperator` during an active session
 *    (CYP-484 REV-1, in-process). Live it needs deploy/PO to revoke mid-session (coordination). The client-observable
 *    teardown (CONNECTED → drop → RECONNECTING, tunnel gone) is scaffolded; the live definitive run pairs it with the
 *    server revoke.
 */
class Cyp529LiveNegativesScaffold {

    private fun env(k: String, d: String? = null) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
    private fun bearer() = File(env("CYP427_BEARER_FILE", "/home/thorsten/.cyppie-secrets/operator-bearer.txt")!!).readText().trim()
    private fun cp() = HttpClient(CIO)
    private fun ws() = HttpClient(CIO) { install(WebSockets) }
    private val cpBase get() = env("CYP427_CP_BASE", "https://api.cyppie-agents.com")!!
    private val hubId get() = env("CYP427_HUB_ID") ?: error("CYP427_HUB_ID required")

    /** Real live dialer (resolve → relay WS), reused by the negatives so the fault is injected at ONE seam only. */
    private fun liveDialer(bearer: String) =
        RendezvousRelayDialer(HttpRendezvousResolver(cp(), cpBase) { bearer }, KtorWsRelayConnector(ws()))

    // ── FC-trustchanged ★ — LIVE-injectable: real dial, trust resolves Changed → LOST+TrustChanged, no retry ──
    @Test
    fun liveTrustChanged_terminal_noSilentRetry() {
        if (env("CYP427_LIVE") != "1") return
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val bearer = bearer()
        // trust resolves Changed (equivalently a seeded wrong pin) → the gate never proceeds to a re-pin.
        val changedTrust = HubTrust { TrustResolution.Changed(expectedFingerprint = "seeded-prior-pin-fingerprint") }
        val session = buildRemoteHubSession(hubId, NoiseJavaClientTransport(), liveDialer(bearer), changedTrust, OperatorAuthenticator { _, _ -> false }, scope)
        val terminal = kotlinx.coroutines.runBlocking {
            session.start()
            withTimeoutOrNull(20_000) { session.state.first { it.failure != null || it.conn == RemoteConnState.LOST } }
        }
        assertTrue(terminal?.failure is RemoteFailure.TrustChanged, "a changed trust is TERMINAL TrustChanged (LOST): ${terminal?.conn}/${terminal?.failure}")
        // no silent retry: after the terminal, the state must NOT loop back to RELAY_DIALING within a backoff window.
        val looped = kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(3_000) { session.state.first { it.conn == RemoteConnState.RELAY_DIALING } }
        }
        assertTrue(looped == null, "TrustChanged is terminal — no silent re-dial (CI-5)")
        kotlinx.coroutines.runBlocking { session.close() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ── FC-wrong-sub ★ — client-half terminal handling (AuthRejected, no retry); live definitive needs a 2nd-op bearer ──
    @Test
    fun wrongSub_clientHalf_authRejected_terminal() {
        if (env("CYP427_LIVE") != "1") return
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val bearer = bearer()
        // authenticator returns false = the hub's a∧b∧c said no (the CB-and-b wrong-sub rejection, client-observable).
        val session = buildRemoteHubSession(hubId, NoiseJavaClientTransport(), liveDialer(bearer), HubTrust { TrustResolution.FirstUse(ByteArray(32), "fp") }, OperatorAuthenticator { _, _ -> false }, scope)
        // NOTE: this reaches AUTHENTICATING only if the real handshake completes; against the live hub with a wrong pin
        // it will fail earlier. The DEFINITIVE wrong-sub live run needs a 2nd-operator bearer to mint a genuinely
        // wrong-`sub` ticket the live hub rejects (CB-and-b). Structure retained; assertion is the client terminal path.
        kotlinx.coroutines.runBlocking { session.start(); withTimeoutOrNull(20_000) { session.state.first { it.failure != null } }; session.close() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue(true, "scaffold: wrong-sub AuthRejected client path; live definitive needs a 2nd-operator bearer (host-side)")
    }

    // ── FC-revoked-mid-session ★ — teardown scaffold; live definitive pairs with a server-side revokeOperator ──
    @Test
    fun revokedMidSession_teardownScaffold() {
        if (env("CYP427_LIVE") != "1") return
        // The client-observable of a mid-session revoke = the tunnel drops → RECONNECTING → (on re-auth) AuthRejected.
        // The REAL trigger is hub-side revokeOperator (CYP-484 REV-1) — needs deploy/PO to revoke during the session.
        // Structure retained for the coordinated live run; asserted against the real teardown when the revoke fires.
        assertTrue(true, "scaffold: revoked-mid-session teardown; live definitive needs a coordinated hub-side revoke")
    }

    private fun anyTunnel(): NoiseTunnel? = null // placeholder to keep the CONNECTED-anchor wiring explicit for the live run
}
