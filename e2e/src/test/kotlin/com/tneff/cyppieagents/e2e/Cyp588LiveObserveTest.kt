package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.StoredAgentEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-588 §3 — **my independent client-side OBSERVE** of the live-gap-detect proof at the dogfood (Team-2).
 *
 * Split (PO1): Team-1-Backend runs the DRIVE (throttled client + >256 burst that forces a real DROP_OLDEST
 * live gap); **I do NOT rebuild the drive.** I attach my OWN read-only `/ws/agent` observer to the SAME
 * **throwaway** test agent and verify — independently of what the drive-client reports — that the delivered
 * transcript is **contiguous 1..N, no hole, no dup** (the backfill outcome). Backend2 covers the server side
 * (the `onGap` WARN + durable count = the real drop). Two independent observers, complementary → non-vacuous:
 * Backend2 proves the >256 drop happened; I prove an independent fresh client still sees a whole record.
 *
 * **READ-ONLY:** connects and reads frames only — never sends a UserTurn (never drives the agent). Gated on
 * `CYP588_OBSERVE_URL` so it is a no-op in CI; the read-only `/ws/agent` access is **deploy-owned** (grant +
 * URL + throwaway agentId + token arrive with the §3 timing from PO1). Run at §3 with:
 *   CYP588_OBSERVE_URL=https://<deploy>  CYP588_OBSERVE_TOKEN=<grant>  CYP588_OBSERVE_AGENT=<throwaway>
 *   CYP588_OBSERVE_SECONDS=<window>  ./gradlew :e2e:test --tests '*Cyp588LiveObserveTest'
 *
 * ⚠ HARD CONSTRAINT (PO1): the agentId MUST be a THROWAWAY test agent, never a default agent (clean dogfood
 * transcripts). The tool refuses to assert against `po`/`frontend`/`backend`.
 */
class Cyp588LiveObserveTest {

    @Test
    fun observeLiveGapDetect_contiguousBackfill_readOnly() {
        val base = System.getenv("CYP588_OBSERVE_URL")
            ?: run { println("CYP-588 §3 OBSERVE: skipped (set CYP588_OBSERVE_URL + _TOKEN + _AGENT to run at §3)"); return }
        val token = requireNotNull(System.getenv("CYP588_OBSERVE_TOKEN")) { "CYP588_OBSERVE_TOKEN required" }
        val agent = requireNotNull(System.getenv("CYP588_OBSERVE_AGENT")) { "CYP588_OBSERVE_AGENT (throwaway) required" }
        require(agent !in setOf("po", "frontend", "backend")) { "refusing to observe a default agent '$agent' — §3 requires a THROWAWAY test agent (PO1)" }
        val seconds = System.getenv("CYP588_OBSERVE_SECONDS")?.toLongOrNull() ?: 90L
        val since = System.getenv("CYP588_OBSERVE_SINCE")?.toLongOrNull() ?: 0L

        val wsBase = base.trim().removeSuffix("/").replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val arrival = CopyOnWriteArrayList<Long>() // seqs in the order the client received them
        val client = HttpClient(CIO) { install(ClientWebSockets); defaultRequest { header("Authorization", "Bearer $token") } }
        try {
            runBlocking {
                withTimeoutOrNull(seconds * 1_000) {
                    client.webSocket("$wsBase/ws/agent?agentId=$agent&since=$since") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                runCatching { CommJson.decodeFromString(StoredAgentEvent.serializer(), frame.readText()).seq }
                                    .getOrNull()?.let { arrival.add(it) }
                            }
                        }
                    }
                }
            }
        } finally {
            client.close()
        }

        val seqs = arrival.toList()
        require(seqs.isNotEmpty()) { "observed 0 frames over ${seconds}s — no stream (check grant / throwaway agentId / that the drive ran in-window)" }
        val set = seqs.toSet()
        val min = seqs.min(); val max = seqs.max()
        val missing = (min..max).filter { it !in set }
        val dups = seqs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()
        var maxArrivalJump = 0L
        for (i in 1 until seqs.size) { val d = seqs[i] - seqs[i - 1]; if (d > maxArrivalJump) maxArrivalJump = d }

        // Evidence line (my client-side OBSERVE piece — pairs with Backend2's server onGap-WARN + durable-count).
        println(
            "CYP-588 §3 OBSERVE (client, read-only): frames=${seqs.size} range=$min..$max " +
                "missing=${missing.size} dups=${dups.size} maxArrivalJump=$maxArrivalJump",
        )
        if (missing.isNotEmpty()) println("  ⚠ MISSING seqs (TRANSCRIPT HOLE): ${missing.take(50)}${if (missing.size > 50) " …(+${missing.size - 50})" else ""}")
        if (dups.isNotEmpty()) println("  ⚠ DUP seqs: $dups")

        assertTrue(missing.isEmpty(), "BACKFILL PROOF (client): the received /ws/agent stream must be contiguous $min..$max — ${missing.size} seq(s) missing = a permanent transcript hole")
        assertTrue(dups.isEmpty(), "no duplicate seq in the received stream (${dups.size} dup(s))")
    }
}
