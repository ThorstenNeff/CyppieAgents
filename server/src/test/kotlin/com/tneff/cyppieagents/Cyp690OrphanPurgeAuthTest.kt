package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.SqliteRemoteTokenStore
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.routing.WireRateLimiter
import com.tneff.cyppieagents.routing.hubWireRoutes
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-690 — boot orphan-purge, pinned at the AUTH OUTCOME.
 *
 * ### The property (not the implementation)
 * After a hub restart, a persisted remote-agent token whose agentId is **not** in the rehydrated roster
 * must **stop authenticating** — and a persisted token whose agentId **is** a live roster participant must
 * **keep** authenticating. Both halves are stated over the real `/ws/hub` auth surface: "the socket is
 * refused" / "the socket is accepted". Nothing here asserts *how* the purge is implemented.
 *
 * ### Why this exists next to `RemoteNoSpawnBootTest.cyp690_...`
 * That tooth pins the **registry lookup** (`agentFor(...) == null`) and proves collateral-freedom using a
 * **config-declared** agent — whose token lives in the boot seed (`secrets.agentTokens`), *never* in
 * `remoteTokenStore`. The purge loop iterates `remoteTokenStore.all()`, so that entry is one the loop
 * **never visits**: its survival is guaranteed by construction and cannot detect an over-broad purge.
 * With exactly one store entry (the orphan, which *should* go), an inverted purge that removes
 * **everything** in the store is invisible to it.
 *
 * This tooth closes both gaps:
 *  - it seeds **multiple** persisted tokens, ≥1 orphaned, and — decisively — **one whose agentId IS in the
 *    roster**, so the purge loop genuinely visits an entry it must keep; and
 *  - it pins the **auth outcome over the wire** (`VIOLATED_POLICY` close / successful handshake), not the
 *    store state or an internal lookup.
 *
 * ### The four quadrants
 *  ① `anchor`      — a persisted store token for a LIVE agent authenticates over the wire after boot.
 *                    Proves the bind loop restores store tokens into the auth path *and* that the fixture
 *                    can say YES — so a later "refused" is a real refusal, not a broken harness.
 *  ② `acceptance`  — both orphaned tokens are REFUSED at `/ws/hub` (`VIOLATED_POLICY`).
 *                    Mutation: defeat the purge → orphans authenticate → this reds.
 *  ③ `collateral`  — the live-roster token **in the same store** is still ACCEPTED at `/ws/hub`.
 *                    Mutation: over-broad purge (drop the roster check) → this reds. ← the gap above.
 *  ④ `durability`  — orphans are removed from the durable store (no resurrection via the next boot's bind
 *                    loop); the live entry is RETAINED (the purge does not silently drain the store).
 *
 * ### Where this tooth stops
 * It pins the **boot-time** outcome for tokens persisted in `remoteTokenStore`, single-project
 * (BYOA Topology-A). It does **not** pin the *at-connect* roster cross-check — `agentFor` still does no
 * roster validation (Auth.kt), so an agent removed from the roster *while the hub runs* is out of scope
 * here; that is CYP-172. It also does not cover the cross-project case (`remoteTokenStore` is global while
 * the purge reads the active project's `state.agents`).
 */
class Cyp690OrphanPurgeAuthTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class NoopSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>) = FakeProcess()
    }

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    /**
     * Boot a platform over a durable remote-token store pre-seeded with [persisted] (agentId → token) —
     * i.e. the post-restart state: the bind loop restores these bindings, then the roster rehydrates, then
     * the CYP-690 purge runs. Config declares `po` (local) + `backend` (remote), so `backend` is the only
     * roster-live agent a persisted token can legitimately belong to.
     */
    private fun bootWithPersisted(persisted: Map<String, String>): Pair<BootedPlatform, File> {
        val root = Files.createTempDirectory("cyp690-auth").toFile()
        val tokensFile = File(root, "remote-tokens.db")
        SqliteRemoteTokenStore(tokensFile.toPath()).use { store ->
            persisted.forEach { (agentId, token) -> store.put(agentId, token) }
        }
        val config = PlatformConfig(
            repo = RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),
                AgentConfig("backend", "BE", Role.WORKER, remote = true),
            ),
        )
        val booted = BootOrchestrator(
            config, secrets(), WorktreeManager(FakeGit(), root), NoopSpawner(), scope,
            remoteTokensFile = tokensFile,
        ).boot()
        return booted to tokensFile
    }

    /** The seed used by ②/③/④: TWO orphans + ONE token for a live roster agent, all in the same store. */
    private fun mixedSeed() = mapOf(
        "ghost" to "tok-ghost",                 // orphan: 'ghost' is not a config agent → never rehydrated
        "phantom" to "tok-phantom",             // orphan #2 — the purge must handle >1
        "backend" to "tok-runtime-backend",     // LIVE: 'backend' IS in the roster → the loop visits, must keep
    )

    /** Mount the REAL `/ws/hub` route over the BOOTED token registry — the actual auth surface. */
    private fun ApplicationTestBuilder.installWireOver(booted: BootedPlatform) {
        application {
            install(WebSockets)
            routing {
                hubWireRoutes(
                    booted.hub, booted.tokenRegistry, booted.capabilityRegistry, ProviderRegistry(),
                    WireRateLimiter(), booted.connectorSessions,
                    com.tneff.cyppieagents.events.EventRecorder(
                        com.tneff.cyppieagents.events.InMemoryEventSink(com.tneff.cyppieagents.events.SystemTimeSource()),
                        CoroutineScope(Dispatchers.Default),
                    ),
                    { "default" },
                )
            }
        }
    }

    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }
    private fun frame(f: WireFrame) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, f))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame

    /**
     * Assert the wire ACCEPTS [token] as an authenticated agent: the handshake completes with an ack.
     *
     * A refused socket surfaces as a closed receive channel, which would otherwise red with an opaque
     * `ClosedReceiveChannelException`. We translate it into the close reason, so a collateral regression
     * reports *why* the agent was locked out instead of leaking a channel exception.
     */
    private suspend fun ApplicationTestBuilder.assertWireAccepts(token: String, why: String) {
        var ack: WireFrame? = null
        withTimeout(20_000) {
            // A refused socket tears the receive channel, which surfaces as a CancellationException INSIDE the
            // session block — where nothing can be suspended on (`closeReason.await()` would just re-throw).
            // So capture the outcome and diagnose out here, in an uncancelled frame.
            try {
                wsClient(this@assertWireAccepts).webSocket("/ws/hub?token=$token") {
                    send(Frame.Text(frame(WireHello(
                        Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON),
                        ProviderInfo("claude", "Claude"),
                    ))))
                    ack = recv()
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            } catch (e: Exception) {
                // fall through to the assertion below — `ack` stays null
            }
        }
        assertTrue(ack != null, "$why — but the socket was REFUSED: no WireAck arrived, i.e. the persisted token no longer authenticates")
        assertIs<WireAck>(ack, why)
    }

    /**
     * Assert the wire REFUSES [token] **at the auth check, before any frame**.
     *
     * ⚠️ The close CODE alone is NOT discriminating: `/ws/hub` closes `VIOLATED_POLICY` for the auth
     * rejection *and* for the handshake timeout (HubWireRoutes: "unauthorized" at :79, "handshake timeout"
     * at :105). A token that is still valid gets ACCEPTED, sends no `WireHello`, and is then timed out —
     * also `VIOLATED_POLICY`. Asserting only the code therefore passes even when the purge is defeated
     * (verified: mutation M1 left a code-only assertion green). We pin the REASON, so this fails the
     * moment the refusal stops being an auth refusal.
     */
    private suspend fun ApplicationTestBuilder.assertWireRefuses(token: String, why: String) {
        withTimeout(20_000) {
            wsClient(this@assertWireRefuses).webSocket("/ws/hub?token=$token") {
                val reason = closeReason.await()
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, why)
                assertEquals("unauthorized", reason?.message, "$why — and refused by the AUTH check, not by the handshake timeout")
            }
        }
    }

    // ---------- ① ANCHOR — the fixture can say YES; the bind loop really restores store tokens ----------
    /**
     * A persisted token for an agent that IS in the roster authenticates over the real wire after boot.
     * Without this, ②'s "refused" would be indistinguishable from a harness that refuses everything.
     */
    @Test
    fun cyp690_1_anchor_persistedTokenForALiveAgent_authenticatesOverTheWire() = testApplication {
        val (booted, _) = bootWithPersisted(mapOf("backend" to "tok-runtime-backend"))
        installWireOver(booted)
        assertWireAccepts("tok-runtime-backend", "ANCHOR: a persisted token for a roster-live agent must authenticate after boot")
    }

    // ---------- ② ACCEPTANCE — the orphan stops authenticating ----------
    /**
     * ★ The CYP-690 property. Both orphaned tokens are refused at the auth surface after restart.
     * Mutation: defeat the purge block in [BootOrchestrator] (`if (false)`) → the bind loop's restored
     * binding survives → `/ws/hub?token=tok-ghost` authenticates → this reds.
     */
    @Test
    fun cyp690_2_orphanedPersistedTokens_areRefusedAtTheWire_afterRestart() = testApplication {
        val (booted, _) = bootWithPersisted(mixedSeed())
        installWireOver(booted)
        assertWireRefuses("tok-ghost", "CYP-690: an orphaned remote token must NOT authenticate after the boot-purge")
        assertWireRefuses("tok-phantom", "CYP-690: the purge must handle every orphan, not just the first")
        // ...and the identity source agrees (the refusal is the purge, not a wire-layer accident).
        assertNull(booted.tokenRegistry.agentFor("tok-ghost"), "the orphaned binding is gone from the sole identity source")
        assertNull(booted.tokenRegistry.agentFor("tok-phantom"), "the second orphaned binding is gone too")
    }

    // ---------- ③ COLLATERAL — a live-roster token IN THE SAME STORE survives ----------
    /**
     * ★ The gap the existing tooth cannot see. `tok-runtime-backend` is persisted in `remoteTokenStore`
     * (so the purge loop genuinely VISITS it) and its agentId `backend` IS a live roster participant, so it
     * must be kept. Mutation: drop the roster check (`if (true)`) → `revoke("backend")` fires → this reds.
     * The existing tooth stays GREEN under that mutation, because its store holds only the orphan.
     */
    @Test
    fun cyp690_3_collateral_liveRosterTokenInTheSameStore_stillAuthenticates() = testApplication {
        val (booted, _) = bootWithPersisted(mixedSeed())
        installWireOver(booted)
        assertWireAccepts("tok-runtime-backend", "CYP-690 collateral: a persisted token whose agent IS in the roster must keep authenticating")
        assertEquals("backend", booted.tokenRegistry.agentFor("tok-runtime-backend"), "the live binding is intact")
        // The purge must also not take out the config-declared seed token as a side effect (revoke() is
        // per-AGENT: a wrongly-purged 'backend' would drop this one too).
        assertEquals("backend", booted.tokenRegistry.agentFor("tok-backend"), "the config-declared seed token is untouched")
    }

    // ---------- ④ DURABILITY — orphans removed, live entry retained ----------
    /**
     * The orphans must not resurrect via the NEXT boot's bind loop, and the purge must not drain the store
     * of legitimate entries. Mutation (over-broad purge) → 'backend' is missing here → this reds.
     */
    @Test
    fun cyp690_4_orphansRemovedFromDurableStore_liveEntryRetained() = testApplication {
        val (_, tokensFile) = bootWithPersisted(mixedSeed())
        val remaining = SqliteRemoteTokenStore(tokensFile.toPath()).use { it.all() }
        assertTrue("ghost" !in remaining.keys, "the orphan is removed from the durable store (no resurrection next boot)")
        assertTrue("phantom" !in remaining.keys, "the second orphan is removed too")
        assertTrue("backend" in remaining.keys, "the live-roster entry is RETAINED — the purge is not a store drain")
    }
}
