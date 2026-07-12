package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.InMemoryRelayChannel
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialException
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-494 — the [RendezvousRelayDialer] logic (resolve → dial), the typed-failure mapping (NOT_REGISTERED →
 * HubOffline · RELAY_UNAVAILABLE → RelayUnreachable), fail-closed (a resolve failure never dials), and that the
 * typed failure reaches the session state as its OWN cause (not the generic RelayUnreachable). The relay WS +
 * message-preserving framing is proven e2e in `Cyp494RelayWsConnectorE2eTest` (real Ktor loopback).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp494RelayDialerTest {

    @Test
    fun dial_bound_opensConnectorWithResolvedUrlAndId_returnsThatChannel() = runTest {
        val (clientChannel, _) = InMemoryRelayChannel.pair()
        var seenUrl: String? = null
        var seenId: String? = null
        val connector = RelayWsConnector { url, id -> seenUrl = url; seenId = id; clientChannel }
        val resolver = RendezvousResolver { RendezvousResolution.Bound("rzv-1", "ws://relay.example/x") }

        val channel = RendezvousRelayDialer(resolver, connector).dial("hub-a")

        assertSame(clientChannel, channel, "the dialer returns the connector's channel")
        assertEquals("ws://relay.example/x", seenUrl, "dials the CP-resolved relay URL")
        assertEquals("rzv-1", seenId, "joins the CP-resolved opaque rendezvous id")
    }

    @Test
    fun dial_notRegistered_throwsRelayDialException_hubOffline() = runTest {
        val dialer = RendezvousRelayDialer(
            resolver = { RendezvousResolution.Failed(RendezvousUnavailable.NOT_REGISTERED) },
            connector = { _, _ -> error("a resolve failure must NOT dial the relay") },
        )
        val e = assertFailsWith<RelayDialException> { dialer.dial("hub-a") }
        assertEquals(RemoteFailure.HubOffline, e.failure, "NOT_REGISTERED → HubOffline (distinct cause)")
    }

    @Test
    fun dial_relayUnavailable_throwsRelayDialException_relayUnreachable() = runTest {
        val dialer = RendezvousRelayDialer(
            resolver = { RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE) },
            connector = { _, _ -> error("a resolve failure must NOT dial the relay") },
        )
        val e = assertFailsWith<RelayDialException> { dialer.dial("hub-a") }
        assertEquals(RemoteFailure.RelayUnreachable, e.failure, "RELAY_UNAVAILABLE → RelayUnreachable (distinct cause)")
    }

    @Test
    fun dial_failed_neverDialsTheRelay_failClosed() = runTest {
        var opened = false
        val dialer = RendezvousRelayDialer(
            resolver = { RendezvousResolution.Failed(RendezvousUnavailable.NOT_REGISTERED) },
            connector = { _, _ -> opened = true; error("x") },
        )
        assertFailsWith<RelayDialException> { dialer.dial("hub-a") }
        assertFalse(opened, "resolve-failed short-circuits before the relay dial (fail-closed)")
    }

    // --- the typed dial failure reaches the session as its own cause (state-machine wiring) ---
    private val unreachedTransport = object : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
            error("dial fails first — transport is never reached")
    }
    private val unreachedTrust = HubTrust { error("never reached") }
    private val unreachedAuth = OperatorAuthenticator { _, _ -> error("never reached") }

    @Test
    fun session_typedDialFailure_surfacesThatCause_notGenericRelayUnreachable() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        scope.launch {
            val session = RemoteHubSession(
                hubId = "hub-a",
                transport = unreachedTransport,
                dialer = { throw RelayDialException(RemoteFailure.HubOffline) },
                trust = unreachedTrust,
                authenticator = unreachedAuth,
                scope = this,
                backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
            )
            session.start()
            session.state.collect { seen.add(it); if (it.failure != null) cancel() }
        }
        advanceUntilIdle()
        assertTrue(
            seen.any { it.failure == RemoteFailure.HubOffline },
            "a RelayDialException(HubOffline) surfaces as HubOffline, not the generic RelayUnreachable",
        )
        scope.cancel()
    }
}
