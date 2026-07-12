package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-486 live-wiring — the jvm assembly is **real but INERT**: [defaultRemoteHubSessionFactory] constructs the
 * real Noise stack (transport/trust/auth all build), yet every connect **fails closed at dial** (the gated
 * relay dialer throws → RelayUnreachable) and never reaches CONNECTED — a flipped flag connects to nothing, not
 * a fake success. And the remote flag is off by default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHubAssemblyTest {

    private val hub = HubDescriptor("hub-1", "host-1", online = true, defaultPort = 8787, lastSeen = 1L)

    @Test
    fun jvmAssembly_constructsRealStack_butFailsClosedAtDial() = runTest {
        val factory = defaultRemoteHubSessionFactory()
        assertNotNull(factory, "jvm provides the real (inert) remote assembly")

        val feed = RemoteHubSessionConnectFeed(factory)
        val seen = mutableListOf<RemoteSessionState>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        scope.launch {
            feed.connect(hub).collect {
                seen.add(it)
                if (it.failure != null) cancel() // stop the fail-closed reconnect loop once it has failed
            }
        }
        advanceUntilIdle()

        assertTrue(
            seen.any { it.failure == RemoteFailure.RelayUnreachable },
            "the gated relay dialer fails closed at dial (no client rendezvous yet)",
        )
        assertFalse(
            seen.any { it.conn == RemoteConnState.CONNECTED },
            "the inert assembly NEVER reaches CONNECTED — a flipped flag connects to nothing",
        )
        scope.cancel()
    }

    @Test
    fun remoteHubEnabled_isOffByDefault() {
        // No CYP_REMOTE_HUB env in the test → the hard off-default holds.
        assertFalse(remoteHubEnabled(), "remote mode must be OFF by default (INERT)")
    }
}
