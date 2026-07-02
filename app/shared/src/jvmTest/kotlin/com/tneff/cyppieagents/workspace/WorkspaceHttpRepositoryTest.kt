package com.tneff.cyppieagents.workspace

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-186 (roster fold) — hermetic end-to-end for [WorkspaceHttpRepository] against an embedded Ktor faking
 * `GET /api/workspace/members` (`:core WorkspaceMember`). Proves the operator path parses the list and — the
 * load-bearing bit — a MEMBER 403 (or any non-2xx) **fails closed to empty** (no partial/leaked roster).
 */
class WorkspaceHttpRepositoryTest {

    private fun withServer(
        handler: io.ktor.server.routing.Routing.() -> Unit,
        block: suspend (WorkspaceHttpRepository) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(Netty, port = 0) { routing { handler() } }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(WorkspaceHttpRepository(client, "http://127.0.0.1:$port", "op-token"))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun members_operator_parsesList() = withServer({
        get("/api/workspace/members") {
            // The operator bearer must be carried.
            assertEquals("Bearer op-token", call.request.header("Authorization"))
            call.respondText(
                """[{"identityId":"11111111-aaaa-2222","tier":"OPERATOR"},{"identityId":"33333333-bbbb-4444","tier":"MEMBER","displayName":"Bob"}]""",
                ContentType.Application.Json,
            )
        }
    }) { repo ->
        val members = repo.members()
        assertEquals(2, members.size)
        assertEquals("11111111-aaaa-2222", members[0].identityId)
        assertEquals("OPERATOR", members[0].tier)
        assertEquals(null, members[0].displayName)
        assertEquals("Bob", members[1].displayName)
    }

    @Test
    fun members_memberForbidden_failsClosedEmpty() = withServer({
        get("/api/workspace/members") {
            call.respondText("""{"error":{"code":"operator_required"}}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
    }) { repo ->
        // ⭐ 403 for a non-operator → empty, NEVER a leaked/partial roster (mutation to "parse anyway" reddens).
        assertTrue(repo.members().isEmpty())
    }

    @Test
    fun members_serverError_failsClosedEmpty() = withServer({
        get("/api/workspace/members") {
            call.respondText("boom", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }
    }) { repo ->
        assertTrue(repo.members().isEmpty())
    }
}
