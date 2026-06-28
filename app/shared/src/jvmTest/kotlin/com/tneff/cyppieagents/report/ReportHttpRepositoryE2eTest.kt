package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSection
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.ReportWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-90 stub→real swap — e2e proof that [ReportHttpRepository] talks the CYP-89 report endpoints against a
 * **real embedded Ktor server** (not a fake), so the swap from [StubReportRepository] is only a constructor
 * change. Covers generate→list(newest-first)→get round-trip, the `GenerateReportRequest` body, and the
 * server reason-code mapping (404 `report_not_found`).
 */
class ReportHttpRepositoryE2eTest {

    @Test
    fun generate_list_get_roundTrips_andMapsErrors() = runBlocking {
        val snapshots = mutableListOf<ReportSnapshot>()
        var counter = 0
        var lastRequestedType: ReportType? = null

        fun err(code: String) = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code)))

        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/reports") {
                    val req = CommJson.decodeFromString(GenerateReportRequest.serializer(), call.receiveText())
                    lastRequestedType = req.type
                    val n = counter++
                    val snap = ReportSnapshot(
                        id = "rep-$n", type = req.type, generatedAt = 1_700_000_000_000L + n * 60_000L,
                        projectId = "default", sources = listOf("events"),
                        window = ReportWindow("boot", "now"),
                        sections = listOf(ReportSection("s", "Section", emptyList())),
                    )
                    snapshots.add(snap)
                    call.respondText(CommJson.encodeToString(ReportSnapshot.serializer(), snap), ContentType.Application.Json, HttpStatusCode.Created)
                }
                get("/api/reports") {
                    val metas = snapshots.sortedByDescending { it.generatedAt }
                        .map { ReportMeta(it.id, it.type, it.generatedAt, "summary") }
                    call.respondText(CommJson.encodeToString(ListSerializer(ReportMeta.serializer()), metas), ContentType.Application.Json)
                }
                get("/api/reports/{id}") {
                    val id = call.parameters.getOrFail("id")
                    val snap = snapshots.firstOrNull { it.id == id }
                    if (snap == null) call.respondText(err("report_not_found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                    else call.respondText(CommJson.encodeToString(ReportSnapshot.serializer(), snap), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = ReportHttpRepository(client, "http://127.0.0.1:$port", token = "op")

                // Generate two snapshots (immutable, fresh per run) → the POST body carried the type.
                val first = repo.generate(ReportType.USAGE)
                assertEquals(ReportType.USAGE, first.type)
                val second = repo.generate(ReportType.DEFECTS)
                assertEquals(ReportType.DEFECTS, lastRequestedType)

                // List is newest-first (the history; none is "the" status).
                val metas = repo.list()
                assertEquals(2, metas.size)
                assertEquals(second.id, metas.first().id)
                assertTrue(metas[0].generatedAt > metas[1].generatedAt)

                // Get the full snapshot by id.
                assertEquals(ReportType.USAGE, repo.get(first.id).type)

                // 404 → mapped to the reason code the VM keys on.
                val notFound = assertFails { repo.get("ghost") }
                assertIs<ReportException>(notFound)
                assertEquals("report_not_found", notFound.code)
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
