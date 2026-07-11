package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.ShellConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-411 (Epic CYP-395 S-H) — the `HubTransport` seam. Proves the *pure-refactor* contract: Local mode exposes
 * exactly the endpoint's URLs + session token, owns vs. injects its client correctly, the endpoint preserves the
 * resolved URLs verbatim (the behaviour-identity anchor), and the Remote seam is fail-loud. The wider "no
 * regression" claim is carried by the full app gate (every existing AgentShell test now runs through the seam).
 */
class Cyp411HubTransportTest {

    @Test
    fun localTransport_exposesEndpointUrls_andPassesSessionToken() {
        val client = HttpClient()
        try {
            val t = LocalHubTransport(
                HubEndpoint("http://h:1234", "ws://h:1234"),
                sessionTokenProvider = { "tok-42" },
                injectedClient = client,
            )
            assertEquals("http://h:1234", t.httpBaseUrl)
            assertEquals("ws://h:1234", t.wsBaseUrl)
            assertEquals("tok-42", t.sessionToken())
            assertSame(client, t.httpClient, "an injected client must be the one exposed")
        } finally {
            client.close()
        }
    }

    @Test
    fun localTransport_ownsAndClosesTheClientItBuilt() {
        val t = LocalHubTransport(HubEndpoint.local(), sessionTokenProvider = { null })
        val owned = t.httpClient
        assertTrue(owned.isActive, "a built client starts active")
        t.close()
        assertFalse(owned.isActive, "close() must tear down the client the transport built (ownsClient)")
    }

    @Test
    fun localTransport_doesNotCloseAnInjectedClient() {
        val client = HttpClient()
        val t = LocalHubTransport(HubEndpoint.local(), injectedClient = client)
        t.close()
        assertTrue(client.isActive, "an injected client is caller-owned; the transport must NOT close it")
        client.close()
    }

    @Test
    fun hubEndpoint_local_buildsSchemeAndPort() {
        val plain = HubEndpoint.local("localhost", 8787)
        assertEquals("http://localhost:8787", plain.httpBaseUrl)
        assertEquals("ws://localhost:8787", plain.wsBaseUrl)
        val secure = HubEndpoint.local("example.com", 443, secure = true)
        assertEquals("https://example.com:443", secure.httpBaseUrl)
        assertEquals("wss://example.com:443", secure.wsBaseUrl)
    }

    @Test
    fun shellConfig_hubEndpoint_preservesExactUrls() {
        // Behaviour-identity anchor: the endpoint is the EXACT resolved URLs, never rebuilt from (host, port).
        val dev = ShellConfig.dev()
        assertEquals(dev.hubHttpBaseUrl, dev.hubEndpoint().httpBaseUrl)
        assertEquals(dev.hubWsBaseUrl, dev.hubEndpoint().wsBaseUrl)
        // The web/origin base has NO explicit port + a page-fixed scheme — a (host,port) rebuild would change it
        // (…:443) and break the same-origin session cookie. The endpoint must pass it through untouched.
        val origin = ShellConfig.forOrigin("https://app.example.com")
        assertEquals("https://app.example.com", origin.hubEndpoint().httpBaseUrl)
        assertEquals("wss://app.example.com", origin.hubEndpoint().wsBaseUrl)
    }

    @Test
    fun remoteTransport_failsLoud_onEveryUse() {
        val remote = RemoteHubTransport()
        assertFailsWith<NotYetAvailableException> { remote.httpBaseUrl }
        assertFailsWith<NotYetAvailableException> { remote.wsBaseUrl }
        assertFailsWith<NotYetAvailableException> { remote.httpClient }
        assertFailsWith<NotYetAvailableException> { remote.sessionToken() }
        remote.close() // close stays a safe no-op even when the transport is unavailable
    }

    @Test
    fun resolver_local_buildsLocalTransportMatchingEndpoint() {
        val client = HttpClient()
        try {
            val t = TransportModeResolver.create(
                HubTransportMode.LOCAL, HubEndpoint("http://h:9", "ws://h:9"),
                sessionToken = { "s" }, injectedClient = client,
            )
            assertEquals("http://h:9", t.httpBaseUrl)
            assertEquals("ws://h:9", t.wsBaseUrl)
        } finally {
            client.close()
        }
    }

    @Test
    fun resolver_remote_isFailLoud() {
        val t = TransportModeResolver.create(HubTransportMode.REMOTE, HubEndpoint.local())
        assertFailsWith<NotYetAvailableException> { t.wsBaseUrl }
    }

    @Test
    fun resolver_defaultMode_isLocalInPhase1() {
        assertEquals(HubTransportMode.LOCAL, TransportModeResolver.defaultMode())
    }
}
