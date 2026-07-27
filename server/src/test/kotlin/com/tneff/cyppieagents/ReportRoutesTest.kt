package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.report.ReportGenerator
import com.tneff.cyppieagents.report.ReportStore
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.reportRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Report endpoints (S16 / CYP-89). All three operator-gated/fail-closed (no token → no report, not a
 * partial); the §2 codes; immutable newest-first history.
 */
class ReportRoutesTest {

    private fun store(): ReportStore {
        val sink = InMemoryEventSink(SystemTimeSource())
        val state = HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")), HubState.OPERATOR_ID, "default")
        val clock = AtomicLong(1_000)
        return ReportStore(ReportGenerator(sink, state, Hub(state, InMemoryMessageStore())), "default", clock = { clock.getAndAdd(60_000) })
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(store: ReportStore) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message))) }
            }
            routing { reportRoutes(store, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true)) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- operator gating (fail-closed — no partial report) ----

    @Test fun post_noToken_401() = testApplication {
        app(store()); val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.post("/api/reports") { contentType(ContentType.Application.Json); setBody(GenerateReportRequest(ReportType.STATUS)) }.status)
    }

    @Test fun post_participant_403() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.post("/api/reports") { bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(GenerateReportRequest(ReportType.STATUS)) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    @Test fun list_participant_403_noPartialReport() = testApplication {
        app(store()); val c = jsonClient()
        assertEquals(HttpStatusCode.Forbidden, c.get("/api/reports") { bearerAuth("tok-fe") }.status)
    }

    @Test fun getById_noToken_401() = testApplication {
        app(store()); val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/reports/rep-0").status)
    }

    // ---- happy / codes / immutability ----

    @Test fun generate_then_list_get_immutableNewestFirst() = testApplication {
        app(store()); val c = jsonClient()
        val s1 = c.post("/api/reports") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(GenerateReportRequest(ReportType.USAGE)) }
        assertEquals(HttpStatusCode.Created, s1.status)
        val snap1 = s1.body<ReportSnapshot>()
        val snap2 = c.post("/api/reports") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(GenerateReportRequest(ReportType.STATUS)) }.body<ReportSnapshot>()

        // Distinct immutable snapshots (new per run), newest first in the list.
        assertTrue(snap1.id != snap2.id)
        val list: List<ReportMeta> = c.get("/api/reports") { bearerAuth("tok-op") }.body()
        assertEquals(listOf(snap2.id, snap1.id), list.map { it.id }, "newest first")

        // get by id returns the full immutable snapshot
        assertEquals(snap1.id, c.get("/api/reports/${snap1.id}") { bearerAuth("tok-op") }.body<ReportSnapshot>().id)
    }

    @Test fun getById_unknown_404_reportNotFound() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.get("/api/reports/nope") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("report_not_found", r.body<ApiErrorBody>().error.code)
    }
}
