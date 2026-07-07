package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-297 — the participant-token WRITE-escalation fix (adjacent to the 234b-2 ①-fix). A participant token's
 * operator-chosen `subject` shared the String namespace of agentIds / [com.tneff.cyppieagents.comm.HubState.OPERATOR_ID]
 * / Kratos identityIds, and its `tier=READ` was enforced NOWHERE — so a token minted `subject="operator"` wrote
 * as the operator into every channel, and `subject="<agentId>"` impersonated that agent. Three defenses, each
 * with its non-vacuous mutation-RED:
 *  - **Zahn 1** — the mint collision guard: the route rejects a reserved subject.
 *  - **Zahn 2** — the reserved `participant:` namespace (**LOAD-BEARING**): the invariant holds STRUCTURALLY even
 *    if a reserved subject reached the store (bypassing the mint guard) — the principal is ≠ OPERATOR_ID / ≠ any
 *    agentId, so its canRead/canWrite is a distinct, fail-closed-empty row inheriting NO foreign grant.
 *  - **Zahn 3** — `tier=READ` enforced at the write gate: a participant token NEVER writes, even if its principal
 *    is explicitly granted `canWrite:true`.
 */
class ParticipantWriteEscalationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val ptStore = ParticipantTokenStore { 1_000L }

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = opToken),
        idp = FakeIdentityProvider(mapOf("sess-mem" to ResolvedIdentity("mem-1", verified = true))),
        roles = store,
        nowMs = { 1_000L },
        participantTokens = ptStore, // the SAME store the mint route + read resolvers use (shared)
    )

    private suspend fun ApplicationTestBuilder.channelsSeenBy(bearer: String) =
        json.parseToJsonElement(client.get("/api/channels") { header("Authorization", "Bearer $bearer") }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.aChannel(): String =
        channelsSeenBy(opToken).first().jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.mint(subject: String) = client.post("/api/participant-tokens") {
        header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody("""{"subject":"$subject"}""")
    }

    // ── Zahn 1 — mint collision guard ────────────────────────────────────────────────────────────────────────
    @Test
    fun mint_rejects_reservedSubjects_butAcceptsAFreeOne() = testApplication {
        val db = Files.createTempFile("cyp297-mint", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        client.get("/api/auth/me") { header("X-Session-Token", "sess-mem") } // ensureAssigned("mem-1") → appears in roles.list()

        assertEquals(HttpStatusCode.Conflict, mint("operator").status, "mint(subject=OPERATOR_ID) must be rejected")
        assertEquals(HttpStatusCode.Conflict, mint("backend").status, "mint(subject=<agentId>) must be rejected")
        assertEquals(HttpStatusCode.Conflict, mint("mem-1").status, "mint(subject=<assigned identityId>) must be rejected")
        assertEquals(HttpStatusCode.Created, mint("byo-free").status, "a non-reserved subject still mints")

        store.close(); Files.deleteIfExists(db)
    }

    // ── Zahn 2 — the reserved namespace is LOAD-BEARING (holds even if a reserved subject reached the store) ───
    @Test
    fun namespace_isLoadBearing_reservedSubjectInheritsNoForeignGrant() = testApplication {
        val db = Files.createTempFile("cyp297-ns", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        // Pre-mint DIRECTLY on the store — bypassing the route's mint guard — so "a reserved subject in the store"
        // is the premise: the NAMESPACE (not the guard) is what must hold. This is the structural proof.
        val opSubjectTok = ptStore.mint("operator")
        val agentSubjectTok = ptStore.mint("backend")

        assertTrue(channelsSeenBy(opToken).isNotEmpty(), "sanity: the real operator sees the spoke channel(s)")
        // subject="operator" resolves to `participant:operator` ≠ OPERATOR_ID → member of nothing → sees NO channel.
        assertTrue(channelsSeenBy(opSubjectTok).isEmpty(), "subject=\"operator\" must NOT inherit the operator's channels (principal is participant:operator) — saw ${channelsSeenBy(opSubjectTok)}")
        // subject="backend" resolves to `participant:backend` ≠ the agentId → does not inherit the agent's channels.
        assertTrue(channelsSeenBy(agentSubjectTok).isEmpty(), "subject=\"backend\" must NOT inherit the agent's channels — saw ${channelsSeenBy(agentSubjectTok)}")

        store.close(); Files.deleteIfExists(db)
    }

    // ── Zahn 3 — tier=READ enforced at the write gate (participant NEVER writes, even if granted canWrite) ─────
    @Test
    fun participantToken_neverWrites_evenWhenGrantedCanWrite() = testApplication {
        val db = Files.createTempFile("cyp297-write", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        val ch = aChannel()
        val tok = ptStore.mint("byo-writer") // principal = participant:byo-writer

        // Operator EXPLICITLY grants the participant principal canWrite:true (+ canRead) on the channel.
        val grant = client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"$ch","agentId":"participant:byo-writer","canRead":true,"canWrite":true}""")
        }
        assertEquals(HttpStatusCode.OK, grant.status)

        // The grant makes it a first-class READER (tier=READ works)…
        assertTrue(channelsSeenBy(tok).any { it.jsonObject["id"]!!.jsonPrimitive.content == ch }, "a granted participant principal CAN read the channel")
        // …but the WRITE gate rejects it DESPITE the explicit canWrite:true grant (tier=READ enforced, not cosmetic).
        val send = client.post("/api/channels/$ch/messages") {
            header("Authorization", "Bearer $tok"); contentType(ContentType.Application.Json); setBody("""{"body":"escalation attempt"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, send.status, "a participant token must be 403 at the write gate EVEN WITH a canWrite grant (tier=READ)")

        store.close(); Files.deleteIfExists(db)
    }

    // ── Regression control — the PO-Assistant's original repro is now denied ──────────────────────────────────
    @Test
    fun originalRepro_operatorSubjectToken_cannotWriteAsOperator() = testApplication {
        val db = Files.createTempFile("cyp297-repro", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        val ch = aChannel()
        // The exact original attack: a token with subject="operator" (here forced into the store, bypassing even
        // the mint guard). Pre-fix it wrote as the operator into every channel. Now: 403 (namespace + tier=READ).
        val evil = ptStore.mint("operator")
        val send = client.post("/api/channels/$ch/messages") {
            header("Authorization", "Bearer $evil"); contentType(ContentType.Application.Json); setBody("""{"body":"i am operator"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, send.status, "the original escalation (subject=\"operator\" → operator-write) is now denied")

        store.close(); Files.deleteIfExists(db)
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp297-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
