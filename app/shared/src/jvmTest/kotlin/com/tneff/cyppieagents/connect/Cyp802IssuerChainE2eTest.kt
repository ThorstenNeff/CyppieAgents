package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.issuer.DescriptorIssuerCheck
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.tneff.cyppieagents.model.HubDescriptor as CoreHubDescriptor

/**
 * CYP-802 (CYP-747 S1c) — the edge-③ FULL-CHAIN e2e: the object-check found the carrier was dropped at
 * `CoreHubDescriptor.toClient()`, so without this the swap would be "green-but-dead" (carrier on the wire, never
 * consumed). This drives the WHOLE chain — `:core HubDescriptor.issuerTrust` (CP wire JSON) → `toClient()` →
 * `connect.HubDescriptor.issuerTrust` → `buildRemoteHubSession` → `DescriptorIssuerCheck` → the terminal
 * `IssuerNotTrusted` block — for real, plus the `toClient`-preserve guard against a silent re-drop.
 */
class Cyp802IssuerChainE2eTest {

    private class CpHubsStub(val hubs: List<CoreHubDescriptor>)

    private suspend fun withCp(stub: CpHubsStub, block: suspend (String) -> Unit) {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/cp/hubs") {
                    call.respondText(
                        CommJson.encodeToString(ListSerializer(CoreHubDescriptor.serializer()), stub.hubs),
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }
        server.start(wait = false)
        try {
            block("http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        } finally {
            server.stop()
        }
    }

    private fun coreHub(issuerTrust: HubIssuerTrust?) = CoreHubDescriptor(
        hubId = "hub-a", name = "Staging Hub", online = true, defaultPort = 8787,
        lastSeen = 1_720_000_000_000L, dhPubKey = "QUJDRA==", issuerTrust = issuerTrust,
    )

    // ---- session fakes (mirror Cyp443RemoteHubSessionTest): reach the issuer arm (dial ok, pin ok), before handshake.
    private class FakeTunnel : NoiseTunnel {
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTransport : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel = FakeTunnel()
    }
    private val pinned = HubTrust { TrustResolution.Pinned(ByteArray(32)) }
    private val grant = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }

    @Test
    fun toClient_preservesIssuerTrust_notDroppedByTheMapping() = runBlocking<Unit> {
        withCp(CpHubsStub(listOf(coreHub(HubIssuerTrust.NOT_TRUSTED)))) { url ->
            val http = HttpClient(CIO)
            try {
                val hub = HttpControlPlaneClient(http, url, operatorToken = { "sess-op" }).hubs().first()
                // If toClient() dropped the passthrough, this would be null (Unknown → proceed) — the produce edge dead.
                assertEquals(HubIssuerTrust.NOT_TRUSTED, hub.issuerTrust, "toClient() must preserve the CYP-804 issuerTrust posture")
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun fullChain_coreNotTrusted_drivesTerminalIssuerNotTrustedBlock() = runBlocking {
        withCp(CpHubsStub(listOf(coreHub(HubIssuerTrust.NOT_TRUSTED)))) { url ->
            val http = HttpClient(CIO)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val hub = HttpControlPlaneClient(http, url, operatorToken = { "sess-op" }).hubs().first()
                // The whole client-produce path: connect.HubDescriptor.issuerTrust → the real check → the block.
                val session = buildRemoteHubSession(
                    hubId = hub.hubId, transport = FakeTransport(), dialer = RelayDialer { NoopRelay() },
                    trust = pinned, authenticator = grant, scope = scope,
                    issuerTrust = DescriptorIssuerCheck(hub.issuerTrust),
                )
                session.start()
                val terminal = withTimeout(5_000) { session.state.first { it.conn == RemoteConnState.LOST } }
                assertEquals(RemoteFailure.IssuerNotTrusted(null), terminal.failure, "an owned-but-issuer-not-trusted hub drives the terminal block, end-to-end")
                assertNull(session.tunnel, "the terminal issuer block leaves no live tunnel")
            } finally {
                scope.cancel(); http.close()
            }
        }
    }

    @Test
    fun fullChain_coreTrusted_proceedsPastTheIssuerGate() = runBlocking {
        withCp(CpHubsStub(listOf(coreHub(HubIssuerTrust.TRUSTED)))) { url ->
            val http = HttpClient(CIO)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val hub = HttpControlPlaneClient(http, url, operatorToken = { "sess-op" }).hubs().first()
                val session = buildRemoteHubSession(
                    hubId = hub.hubId, transport = FakeTransport(), dialer = RelayDialer { NoopRelay() },
                    trust = pinned, authenticator = grant, scope = scope,
                    issuerTrust = DescriptorIssuerCheck(hub.issuerTrust),
                )
                session.start()
                // TRUSTED proceeds past the issuer gate → the happy path reaches CONNECTED (no issuer block).
                val connected = withTimeout(5_000) { session.state.first { it.conn == RemoteConnState.CONNECTED } }
                assertNull(connected.failure, "a trusted issuer produces no failure")
            } finally {
                scope.cancel(); http.close()
            }
        }
    }
}
