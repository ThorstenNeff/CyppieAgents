package com.tneff.cyppieagents.acl

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-293 (QA — the missing AclWsClient client-level guard) — e2e proof that [AclWsClient] maps a **1008**
 * (VIOLATED_POLICY) close on `/ws/comm` to [AclLiveEvent.AccessRevoked], NOT a generic Disconnected. This is
 * the ACL twin of `EventsWsClientE2eTest.close1008_mapsToAccessRevoked` — it was MISSING: `AclAccessRevokedTest`
 * is VM-level (its stub emits AccessRevoked directly), so it does NOT exercise the client's real close-code
 * mapping. Without this, the CYP-293 shared-`WsClose`-helper retrofit of AclWsClient (or the original inline
 * 1008 check) could break the ACL 1008→AccessRevoked mapping and ship silently — reintroducing the CYP-289
 * reconnect-hammer (a revoked operator token degraded to Disconnected → `.reconnecting()` loops `/ws/comm`).
 * Mutation proof: `if (isAccessRevoked(closeCode))` → `if (false)` in AclWsClient → this REDs.
 */
class AclWsClientE2eTest {

    @Test
    fun close1008_mapsToAccessRevoked() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/comm") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "operator token required"))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AclWsClient(client, "ws://127.0.0.1:$port", token = "bad")
                val events = withTimeout(10_000) { ws.events().toList() }

                // Fail-closed: the ACL stream ends in AccessRevoked, NOT a generic Disconnected (the terminal
                // 1008 the VM cancels the collector on — no reconnect loop).
                assertTrue(events.first() is AclLiveEvent.Connected)
                assertTrue(events.last() is AclLiveEvent.AccessRevoked, "1008 close → AccessRevoked")
                assertTrue(events.none { it is AclLiveEvent.Disconnected }, "a 1008 must NOT degrade to Disconnected")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
