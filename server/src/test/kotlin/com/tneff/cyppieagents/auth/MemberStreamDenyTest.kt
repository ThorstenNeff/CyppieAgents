package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installAgentSocket
import com.tneff.cyppieagents.routing.tokenAuthorize
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-186 (S18 kick-off / BE1) — **Terminals = NO, enforced on the stream itself.** The read-ceiling denies a
 * read-only MEMBER the agent/terminal stream (agent stdout is not secret-free-guaranteed). That surface is the
 * `/ws/agent` (and `/ws/events`) WebSocket, which is **token-gated** (`tokenAuthorize`: operator token, or an
 * agent token matching the requested agent). A human MEMBER authenticates with a Kratos **session**
 * (`X-Session-Token` / cookie), never a bearer/query token — so even WITH a valid session it is not authorized
 * and the socket closes `VIOLATED_POLICY`. This is the enforced+tested counterpart to the doc-table row (the
 * `/api` 403-matrix can't cover it — the streams live under `/ws`, not `/api`).
 */
class MemberStreamDenyTest {

    @Test
    fun memberSession_cannotOpenAgentStream_failClosed() = testApplication {
        val registry = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")
        application { installAgentSocket(ConnectorSessions(), authorize = tokenAuthorize(registry)) }
        val client = createClient { install(ClientWebSockets) }

        // A human MEMBER carries a valid Kratos session header — but NO operator/agent token. The stream gate
        // sees no token → closes VIOLATED_POLICY. (An operator/agent token would be a machine, not a MEMBER.)
        client.webSocket("/ws/agent?agentId=backend", request = { header("X-Session-Token", "sess-member") }) {
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code,
                closeReason.await()?.code,
                "a human MEMBER session must NOT open the agent stream (Terminals=NO — stdout is not secret-free)",
            )
            close()
        }
    }
}
