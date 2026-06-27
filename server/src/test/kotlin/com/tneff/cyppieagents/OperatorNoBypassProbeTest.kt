package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QA verification of the CYP-18 operator-viewer contract: the operator must be a PRIVILEGED
 * AclMatrix PARTICIPANT, *not* an ACL bypass. The definitive proof is behavioral — if the
 * operator's own ACL on a channel is revoked, it must LOSE access (because every read flows
 * through the same AclMatrix). A bypass implementation would keep seeing it and fail this probe.
 */
class OperatorNoBypassProbeTest {

    private fun config() = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = InMemoryMessageStore(),
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun operatorAccessIsAclGoverned_notBypass() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()

        // Baseline: operator (privileged participant) can read po-frontend.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/channels/po-frontend/messages") { bearerAuth("tok-op") }.status,
            "operator should start with read on po-frontend",
        )

        // Revoke the OPERATOR's own ACL on po-frontend via the operator endpoint.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-frontend", "operator", canRead = false, canWrite = false))
        }
        assertEquals(HttpStatusCode.OK, put.status)

        // Decisive: if access is ACL-governed (participant, not bypass), the operator now LOSES read.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/channels/po-frontend/messages") { bearerAuth("tok-op") }.status,
            "BYPASS DETECTED: operator still reads po-frontend after its ACL was revoked",
        )

        // ...and po-frontend drops out of the operator's channel list, leaving only po-backend.
        val chans: List<Channel> = client.get("/api/channels") { bearerAuth("tok-op") }.body()
        assertEquals(listOf("po-backend"), chans.map { it.id }, "revoked channel must disappear from operator view")

        // A genuine member (po) is unaffected — the change was scoped to the operator participant only.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/channels/po-frontend/messages") { bearerAuth("tok-po") }.status,
            "revoking operator ACL must not affect a real member",
        )
    }
}
