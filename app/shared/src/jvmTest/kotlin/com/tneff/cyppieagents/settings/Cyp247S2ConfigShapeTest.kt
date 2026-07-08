package com.tneff.cyppieagents.settings

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-247 S2 — the fake-shape catch for the ADDITIVE config DTOs. The existing
 * [ConfigHttpRepositoryE2eTest] only ever encodes `reprovisionPending=false` (the default), so it never
 * proves the REAL client tolerates a server that SETS the new field true. This drives the real
 * [ConfigHttpRepository] against a `RepoConfigView(reprovisionPending=true)` (encoded through the shared
 * `:core` serializer + [CommJson], exactly what the real ConfigRoutes emits) and asserts:
 *  - the existing config read is NOT broken by the additive field (getRepo → Configured, no decode throw);
 *  - [RepoConfigRequest.discardUnpushed] serializes on the wire (real CommJson round-trip, both directions).
 *
 * Single `:core` DTO both-sided → no shape drift is possible, but this pins it (additive-safe) rather than
 * assuming it — the recurring fake-shape lesson.
 */
class Cyp247S2ConfigShapeTest {

    @Test
    fun realClient_decodesReprovisionPendingTrue_withoutBreakingTheExistingRead() = runBlocking {
        // A server that SETS the new additive field true (encoded via the REAL :core serializer + CommJson).
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/config/repo") {
                    val view = RepoConfigView(
                        configured = true, url = "git@github.com:o/new.git", branch = "dev", reprovisionPending = true,
                    )
                    call.respondText(CommJson.encodeToString(RepoConfigView.serializer(), view), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = ConfigHttpRepository(client, "http://127.0.0.1:$port", token = "op")
                // The real client decodes the new field cleanly and the existing read is intact.
                val state = repo.getRepo()
                assertIs<RepoConfigState.Configured>(state, "additive reprovisionPending must NOT break the existing config read")
                assertEquals("git@github.com:o/new.git", state.url)
                assertEquals("dev", state.branch)
            } finally { client.close() }
        } finally { server.stop(0, 0) }
    }

    @Test
    fun repoConfigRequest_discardUnpushed_serializesBothDirections() {
        // Wire encode via the REAL shared CommJson → the flag is on the wire → decodes back true.
        val json = CommJson.encodeToString(RepoConfigRequest.serializer(), RepoConfigRequest("git@github.com:o/r.git", "dev", discardUnpushed = true))
        assertTrue(json.contains("\"discardUnpushed\":true"), "discardUnpushed must serialize onto the wire: $json")
        val back = CommJson.decodeFromString(RepoConfigRequest.serializer(), json)
        assertEquals(true, back.discardUnpushed, "discardUnpushed round-trips")
        // Additive/back-compat: an OLD body without the field decodes to the safe default (false).
        val legacy = CommJson.decodeFromString(RepoConfigRequest.serializer(), "{\"url\":\"git@github.com:o/r.git\",\"branch\":\"main\"}")
        assertEquals(false, legacy.discardUnpushed, "absent discardUnpushed → safe default false (no forced discard)")
    }
}
