package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installEvents
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-719 F-A (REST surface) — `GET /api/events` ACL-filters comm.* through [installEvents]/`aclQuery` against a
 * real route. A MEMBER sees only comm events in channels it may `canRead`; the operator bypasses. Unwiring the
 * filter (revert `aclQuery` → the raw `sink.query`) reddens `t1_*` (chX would leak to a non-reader). T7 pins the
 * paging correctness across denied rows.
 */
class Cyp719RestAclFilterTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)
    private val active = "alpha"

    // backend may read chY, NOT chX.
    private val acl = AclMatrix(
        channels = listOf(
            Channel("chX", "X", ChannelKind.GROUP, listOf("backend"), projectId = active),
            Channel("chY", "Y", ChannelKind.GROUP, listOf("backend"), projectId = active),
        ),
        entries = listOf(
            AclEntry("chX", "backend", canRead = false, canWrite = false, projectId = active),
            AclEntry("chY", "backend", canRead = true, canWrite = false, projectId = active),
        ),
        activeProjectId = active,
    )

    private fun ApplicationTestBuilder.restClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) {
        application { installEvents(sink, registry, activeProjectId = { active }, acl = { acl }) }
    }

    private fun comm(channel: String) =
        draft(agent = "backend", team = active, type = EventType.COMM_SENT, detail = buildJsonObject { put("channel", channel) })

    private fun channelOf(e: com.tneff.cyppieagents.model.Event) = (e.detail["channel"] as? JsonPrimitive)?.contentOrNull

    @Test fun t1_memberSeesOnlyReadableComm_plusNonComm_operatorSeesAll() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        serve(sink)
        val rest = restClient()
        time.clock = 100; sink.append(comm("chX"))                                                  // denied
        time.clock = 200; sink.append(comm("chY"))                                                  // allowed
        time.clock = 300; sink.append(draft(agent = "backend", team = active, type = EventType.TOOL_CALL)) // non-comm → allowed

        val member: EventPage = rest.get("/api/events") { bearerAuth("tok-be") }.body()
        assertTrue(member.events.none { it.type == EventType.COMM_SENT && channelOf(it) == "chX" }, "chX comm must NOT leak to a non-reader")
        assertTrue(member.events.any { it.type == EventType.COMM_SENT && channelOf(it) == "chY" }, "chY comm IS visible (anti-vacuity)")
        assertTrue(member.events.any { it.type == EventType.TOOL_CALL }, "non-comm metadata still visible (anti-vacuity)")

        val op: EventPage = rest.get("/api/events") { bearerAuth("tok-op") }.body()
        assertEquals(3, op.events.size, "operator bypasses the comm filter → sees all three")
    }

    // T7: interleave allowed(chY)/denied(chX) comm events; page a MEMBER at limit=2. The over-fetch fills each
    // page with only readable rows, the concatenation is exactly the readable set in seq order (no gap/dup, no
    // denied leak), and the cursor terminates (nextAfterSeq null on the last page).
    @Test fun t7_pagingAcrossDeniedRows_noLeak_cursorTerminates() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        serve(sink)
        val rest = restClient()
        // seq 1..6 = chY, chX, chY, chX, chY, chX → readable seqs = 1, 3, 5.
        val chans = listOf("chY", "chX", "chY", "chX", "chY", "chX")
        chans.forEachIndexed { i, ch -> time.clock = (i + 1) * 10L; sink.append(comm(ch)) }

        val p1: EventPage = rest.get("/api/events?limit=2") { bearerAuth("tok-be") }.body()
        assertEquals(listOf(1L, 3L), p1.events.map { it.seq }, "page 1 = the first two readable rows")
        assertEquals(3L, p1.nextAfterSeq, "cursor resumes after the last KEPT seq")

        val p2: EventPage = rest.get("/api/events?limit=2&afterSeq=${p1.nextAfterSeq}") { bearerAuth("tok-be") }.body()
        assertEquals(listOf(5L), p2.events.map { it.seq }, "page 2 = the last readable row")
        assertEquals(null, p2.nextAfterSeq, "the scan drained → cursor terminates")

        val all = p1.events + p2.events
        assertTrue(all.all { channelOf(it) == "chY" }, "no denied (chX) row leaks across paging")
        assertEquals(listOf(1L, 3L, 5L), all.map { it.seq }, "concatenation = the readable set, strictly increasing, no gap/dup")
    }
}
