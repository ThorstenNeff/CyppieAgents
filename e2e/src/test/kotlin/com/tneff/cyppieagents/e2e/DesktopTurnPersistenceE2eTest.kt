package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop-Hub E2E — **turn → persistence** over the REAL embedded platform (Team-2, Post-Login-Lane).
 *
 * The mandate's target class is the one that *breaks live but stays green in a unit test*:
 * silent-swallow / absence-of-signal. A hub turn can `POST` `201` and read back green **in-memory**, yet
 * never reach the durable store — invisible until a genuine restart re-reads from disk. These teeth close
 * that gap end-to-end: a turn is posted through the real `POST /api/channels/{id}/messages`, the platform is
 * **re-boot()ed** over the SAME durable [com.tneff.cyppieagents.comm.JsonFileMessageStore] (reused gitRoot),
 * and the turn is asserted **through the real `GET`** — never a store internal. The `JsonFileMessageStore`
 * durability is unit-pinned by `server/PersistenceTest`; the NET-NEW value here is proving it **through the
 * running server's HTTP surface across a real reboot** (the live path the unit test cannot exercise).
 *
 * Non-vacuity discipline (our hard rule): every restart tooth first establishes the precondition POSITIVELY
 * on the live path (the turn is readable BEFORE any restart), then asserts survival. [coldStart_*] is the
 * paired control — the send/read route works with NO persistence at all, so a restart tooth going red under a
 * mutation proves the *reload* path, not a broken route.
 *
 * Keystone mutation (run, then reverted — prod left pristine): drop the `flush()` in
 * `JsonFileMessageStore.append` → the durable write silently doesn't happen. Boot-1's live `GET` stays GREEN
 * (in-memory), [coldStart_*] stays GREEN, and BOTH restart teeth go RED (boot-2 reads empty). That signature —
 * *live-green, restart-red* — is exactly the absence-of-signal class this covers.
 *
 * Fake connector/git/spawner via `e2ePlatform` (no real `claude`, no repo, no key) → CI-green now. A
 * REAL-`claude` turn round-trip is the live-stack tooth (flagged to the coordinator), not closable in CI.
 */
class DesktopTurnPersistenceE2eTest {

    private val channel = "po-backend"

    /** One PO + one worker → hub-and-spoke seeds the `po-backend` spoke both are members of. */
    private fun projects() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    // ---- everything through the REAL HTTP path ----

    private suspend fun E2ePlatform.postTurn(agentId: String, text: String): Message =
        asAgent(agentId).use { c ->
            val r = c.post("$baseUrl/api/channels/$channel/messages") {
                contentType(ContentType.Application.Json); setBody(SendMessageRequest(text))
            }
            check(r.status == HttpStatusCode.Created) { "POST turn '$text' as $agentId → ${r.status}" }
            // CYP-765: POST returns the CYP-744 DeliveredMessage envelope; unwrap so the durability
            // assertions below keep asserting on the MESSAGE itself (from/body), unchanged in substance.
            r.body<DeliveredMessage>().message
        }

    private suspend fun E2ePlatform.readTurns(agentId: String): List<Message> =
        asAgent(agentId).use { it.get("$baseUrl/api/channels/$channel/messages").body<List<DeliveredMessage>>() }
            .map { it.message }

    @Test
    fun aTurnSurvivesARealServerRestart_readBackThroughTheRealGet() = runBlocking {
        val dir = Files.createTempDirectory("cyp-desktop-turn-persist").toFile()
        val messageStore = File(dir, ".cyppie/messages.json")
        val needle = "deploy the M2 tunnel branch"
        try {
            // ---- Boot 1: establish the precondition POSITIVELY on the live path, THEN restart.
            e2ePlatform(projects(), gitRootOverride = dir, fileBacked = true, messageStoreFile = messageStore).use { p1 ->
                val posted = p1.postTurn("backend", needle)
                assertEquals(needle, posted.body, "the POST echoes the server-stamped turn")
                assertEquals("backend", posted.from, "the sender is the bearer identity (server-stamped), not client-supplied")
                // Live readback BEFORE any restart — proves the turn actually surfaces through GET (non-vacuous).
                assertTrue(
                    p1.readTurns("po").any { it.from == "backend" && it.body == needle },
                    "precondition: the posted turn is live-readable through the real GET before any restart",
                )
            }

            // ---- Boot 2: a REAL re-boot() over the SAME durable store — no in-memory carry-over survives close().
            e2ePlatform(projects(), gitRootOverride = dir, fileBacked = true, messageStoreFile = messageStore).use { p2 ->
                assertTrue(
                    p2.readTurns("po").any { it.from == "backend" && it.body == needle },
                    "the turn survives a real server restart and is read back through the real GET " +
                        "(absence-of-signal guard: a live-green post that never reached disk would fail HERE)",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multipleTurnsSurviveRestart_inInsertionOrder() = runBlocking {
        val dir = Files.createTempDirectory("cyp-desktop-turns-order").toFile()
        val messageStore = File(dir, ".cyppie/messages.json")
        val bodies = listOf("turn-A launch", "turn-B connect", "turn-C enroll")
        try {
            e2ePlatform(projects(), gitRootOverride = dir, fileBacked = true, messageStoreFile = messageStore).use { p1 ->
                bodies.forEach { p1.postTurn("backend", it) }
                assertEquals(
                    bodies, p1.readTurns("po").filter { it.from == "backend" }.map { it.body },
                    "precondition: all turns are live-readable in insertion order before restart",
                )
            }
            e2ePlatform(projects(), gitRootOverride = dir, fileBacked = true, messageStoreFile = messageStore).use { p2 ->
                assertEquals(
                    bodies, p2.readTurns("po").filter { it.from == "backend" }.map { it.body },
                    "every turn — and its order — survives the real restart, read through the real GET",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun coldStart_postIsImmediatelyReadable_nonVacuityControl() = runBlocking {
        // The paired positive control: with NO durable store (messageStoreFile = null → InMemory) and NO
        // restart, a posted turn is immediately readable. So a restart tooth reddening under the keystone
        // mutation localizes to the RELOAD path, not a broken send/read route (and this control stays GREEN
        // under that mutation — in-memory append is untouched).
        val dir = Files.createTempDirectory("cyp-desktop-cold").toFile()
        try {
            e2ePlatform(projects(), gitRootOverride = dir, fileBacked = true).use { p ->
                val needle = "cold turn"
                p.postTurn("backend", needle)
                assertTrue(
                    p.readTurns("po").any { it.from == "backend" && it.body == needle },
                    "cold-start: a posted turn is immediately readable through the real GET (route intact)",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
