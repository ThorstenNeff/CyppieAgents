package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-764 §3 — pins the `GET /api/channels/{id}/messages?since=` CONTRACT, after the M1.7 poll returned
 * nothing for a message that was demonstrably in the channel 5 s earlier.
 *
 * The parameter has **two silent failure directions**, and a caller cannot tell either from a `200 []`:
 *  - **too LARGE** (a wrong unit — µs/ns instead of epoch **milliseconds**) ⇒ `ts > since` is never true ⇒
 *    **empty**, indistinguishable from "the agent never answered". This is the M1.7 symptom.
 *  - **unparseable** (`toLongOrNull()` ⇒ null ⇒ *no filter at all*) ⇒ the **FULL history** comes back, so a
 *    `contains(MARK)` probe PASSES for the wrong reason — a false green.
 *
 * Neither direction 4xx's, so no script can detect a malformed `since` from the status code. These teeth
 * pin the semantics so the next script inherits a documented contract instead of re-deriving it (the
 * "same defect migrates to the next script" concern).
 *
 * **Unit is epoch MILLISECONDS compared against `Message.ts` — NOT `Message.seq`.** `seq` is the read-cursor
 * ordinal (CYP-705); the two are different axes and mixing them is exactly a wrong-unit bug.
 */
class Cyp764SinceSemanticsTest {

    private fun config(store: MessageStore = InMemoryMessageStore()) = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = store,
    loopbackPosture = true)

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private suspend fun io.ktor.client.HttpClient.send(text: String) =
        post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(text))
        }.body<DeliveredMessage>().message

    private suspend fun io.ktor.client.HttpClient.read(since: String?): List<DeliveredMessage> {
        val q = if (since == null) "" else "?since=$since"
        return get("/api/channels/po-backend/messages$q") { bearerAuth("tok-po") }.body()
    }

    // The unit is epoch MILLISECONDS against Message.ts, and the comparison is EXCLUSIVE (`ts > since`).
    // Deliberately timing-INDEPENDENT: two sends can land in the SAME millisecond on a fast machine, so this
    // asserts only properties that hold either way (a wall-clock-dependent tooth would be a flake, and a
    // flaky tooth is worse than none — it teaches the team to re-run instead of to read).
    // Mutant: `>=` instead of `>` → the boundary message reappears at since == its own ts → reds.
    @Test fun sinceIsEpochMillisAgainstTs_andExclusive() = testApplication {
        application { installComm(config()) }
        val c = jsonClient()
        val m1 = c.send("first")
        val m2 = c.send("second")
        val maxTs = maxOf(m1.ts, m2.ts)

        // Strictness: at since == maxTs, NO message can survive (every ts <= maxTs), regardless of whether
        // the two shared a millisecond. This is the exclusivity property, stated flake-free.
        assertEquals(emptyList(), c.read(maxTs.toString()).map { it.message.id }, "since == maxTs must exclude ALL (strict >)")

        // Unit: one millisecond BELOW maxTs must re-admit exactly the newest message(s) — proving the filter
        // moves on a MILLISECOND scale, not a seq scale (a 1-step change on the seq axis would be invisible here).
        val justBelow = c.read((maxTs - 1).toString()).map { it.message.id }.toSet()
        assertEquals(
            listOf(m1, m2).filter { it.ts == maxTs }.map { it.id }.toSet(), justBelow,
            "since == maxTs-1 must admit exactly the message(s) stamped at maxTs (ms-scale filtering on ts)",
        )
    }

    // ★ THE M1.7 SYMPTOM: a wrong-unit `since` (microseconds/nanoseconds instead of ms) silently yields an
    // EMPTY page — identical on the wire to "the agent never replied". This is why the poll missed a message
    // that was in the channel.
    // Mutant that REDS this: DROP an out-of-range `since` (`takeIf { it <= now }` → null → no filter → the
    // full history comes back). Verified red.
    // Mutant that does NOT red it, and the distinction matters: CLAMPING to `now` (`coerceAtMost(now)`) keeps
    // the page empty, because every stored `ts` is <= now anyway. So clamping is NOT a fix for this failure —
    // it silently preserves the exact symptom. Recorded here because the near-miss "fix" is the tempting one.
    @Test fun aWrongUnitSince_yieldsEmpty_notEverything() = testApplication {
        application { installComm(config()) }
        val c = jsonClient()
        val m = c.send("the reply carrying the MARK")
        assertTrue(c.read(null).isNotEmpty(), "sanity: the message IS in the channel (else this tooth is vacuous)")

        val micros = m.ts * 1_000          // as `date +%s%6N` would produce
        val nanos = m.ts * 1_000_000       // as `date +%s%N`  would produce  ← the classic bash slip
        assertEquals(emptyList(), c.read(micros.toString()).map { it.message.id }, "µs-valued since silently returns EMPTY")
        assertEquals(emptyList(), c.read(nanos.toString()).map { it.message.id }, "ns-valued since silently returns EMPTY")
    }

    // ★ THE OPPOSITE SILENT FAILURE: an unparseable `since` is dropped (toLongOrNull → null) and the server
    // returns the FULL history with 200 — so a `contains(MARK)` probe passes for the wrong reason.
    // Mutant: 400 on a malformed since → this reds (and that would arguably be the better contract).
    @Test fun anUnparseableSince_isSilentlyIgnored_returningEverything() = testApplication {
        application { installComm(config()) }
        val c = jsonClient()
        c.send("one"); c.send("two")
        for (bad in listOf("abc", "", "12.5", "1e12")) {
            val r = c.read(bad)
            assertEquals(2, r.size, "since='$bad' is unparseable ⇒ filter DROPPED ⇒ full history (a silent false-green)")
        }
    }

    // seq and ts are DIFFERENT axes: passing a `seq` where epoch-ms is expected is under-filtering, not
    // over-filtering — it silently returns everything, because a small seq is < every real epoch-ms `ts`.
    @Test fun passingASeqWhereMillisAreExpected_underFilters() = testApplication {
        application { installComm(config()) }
        val c = jsonClient()
        val m = c.send("only")
        assertTrue(m.seq in 1..1000, "sanity: seq is a small ordinal, not an epoch value (seq=${m.seq})")
        assertEquals(1, c.read(m.seq.toString()).size, "a seq-valued since is < every ts ⇒ no filtering at all")
    }
}
