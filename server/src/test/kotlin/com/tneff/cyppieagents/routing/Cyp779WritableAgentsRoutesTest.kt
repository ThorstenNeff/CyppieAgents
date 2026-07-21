package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.KratosSettingsClient
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.auth.SqliteRoleStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-779 — `GET /api/agents/writable` (the composer-enable seam at AGENT granularity, sibling of CYP-273's
 * `/channels/writable`). Returns the agent ids the resolved caller may currently SEND to. "Send to agent X" =
 * post into X's hub-and-spoke channel `po-<X>`, which the chokepoint gates with `canWrite` — so an agent is
 * writable ⟺ the caller may write its spoke. The endpoint's answer must MATCH the real send outcome
 * (single-source, no second traversal), be fail-closed for participants and spoke-less agents, and carry the
 * FIXED PL-0068 contract `{agentIds: string[]}`.
 */
class Cyp779WritableAgentsRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val memberId = "carol-mem"
    private val ptStore = ParticipantTokenStore { 1_000L }

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken),
        idp = FakeIdentityProvider(mapOf("sess-carol" to ResolvedIdentity(memberId, verified = true))),
        roles = store, nowMs = { 1_000L },
        participantTokens = ptStore,
    )

    private val op get() = "Authorization" to "Bearer $opToken"
    private val carol get() = "X-Session-Token" to "sess-carol"

    /** The raw response element — so a tooth can pin the CONTRACT SHAPE (`{agentIds:[...]}`), not just the ids. */
    private suspend fun ApplicationTestBuilder.writableRaw(hdr: Pair<String, String>) =
        json.parseToJsonElement(client.get("/api/agents/writable") { header(hdr.first, hdr.second) }.bodyAsText())

    private suspend fun ApplicationTestBuilder.writableAgents(hdr: Pair<String, String>): List<String> =
        writableRaw(hdr).jsonObject["agentIds"]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.allAgentIds(hdr: Pair<String, String>): List<String> =
        json.parseToJsonElement(client.get("/api/agents") { header(hdr.first, hdr.second) }.bodyAsText())
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.grant(ch: String, canRead: Boolean, canWrite: Boolean) = client.put("/api/acl") {
        header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
        setBody("""{"channelId":"$ch","agentId":"$memberId","canRead":$canRead,"canWrite":$canWrite}""")
    }

    /** The AUTHORITATIVE send-to-agent path: post into the agent's inbound spoke. 201 ⟺ the send is allowed.
     *  Mirrors [com.tneff.cyppieagents.comm.HubState.spokeChannelFor]: a worker's `po-<id>`; the PO's `op-po`
     *  (CYP-787) — so the parity check exercises the REAL channel each agent is reached on. */
    private fun channelToReach(agentId: String) = if (agentId == "po") "op-po" else "po-$agentId"
    private suspend fun ApplicationTestBuilder.sendToAgent(agentId: String, hdr: Pair<String, String>) =
        client.post("/api/channels/${channelToReach(agentId)}/messages") {
            header(hdr.first, hdr.second); contentType(ContentType.Application.Json); setBody("""{"body":"hi"}""")
        }

    /**
     * The seam's core invariant — for EVERY agent, `id ∈ writableAgents` ⟺ the real POST-to-spoke is 201. This
     * is the single-source / anti-drift tooth (Zahn 2): the composer's enable prediction can never diverge from
     * the send chokepoint's actual decision, because both resolve through the same `spokeChannelFor` + canWrite.
     */
    private suspend fun ApplicationTestBuilder.assertSendParity(label: String, hdr: Pair<String, String>) {
        val writable = writableAgents(hdr).toSet()
        for (id in allAgentIds(hdr)) {
            val sent201 = sendToAgent(id, hdr).status == HttpStatusCode.Created
            assertEquals(id in writable, sent201, "$label: parity break on agent '$id' — inWritable=${id in writable} sent201=$sent201")
        }
    }

    @Test
    fun contract_isExactlyAgentIdsObject_notABareArray() = testApplication {
        val db = Files.createTempFile("cyp779-contract", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        val body = writableRaw(op).jsonObject
        // PL-0068: the Dev composer is built against `{agentIds: string[]}` — the field name is load-bearing.
        assertEquals(setOf("agentIds"), body.keys, "the response is EXACTLY {agentIds:[...]}, no more/other fields")
        assertTrue(body["agentIds"]!!.jsonArray.all { it.jsonPrimitive.isString }, "agentIds is a string[]")
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun filterBites_memberSeesOnlyCanWriteAgents_andPositiveControlNonEmpty() = testApplication {
        val db = Files.createTempFile("cyp779-filter", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") } // verify → MEMBER

        // Before any grant: MEMBER may write no agent (fail-closed).
        assertTrue(writableAgents(carol).isEmpty(), "MEMBER may write no agent before any grant")

        // Grant canWrite on frontend's spoke, canRead-ONLY on backend's spoke.
        assertEquals(HttpStatusCode.OK, grant("po-frontend", canRead = true, canWrite = true).status)
        assertEquals(HttpStatusCode.OK, grant("po-backend", canRead = true, canWrite = false).status)

        val writable = writableAgents(carol).toSet()
        // FILTER (Zahn 1): the canWrite agent is in; the canRead-only agent is NOT (mutation "return all agents" → red).
        assertTrue("frontend" in writable, "the canWrite-granted agent is writable")
        assertFalse("backend" in writable, "the canRead-ONLY agent must NOT be writable (this is the seam)")
        // POSITIVE CONTROL (Zahn 3): a caller with ≥1 allowed edge yields a NON-EMPTY set, so the filter above
        // is not vacuously green (an always-empty result would also pass the assertFalse).
        assertTrue(writable.isNotEmpty(), "positive control: a caller with a canWrite edge gets a non-empty set")

        // SINGLE-SOURCE (Zahn 2): writable ⟺ the actual send-to-spoke outcome, for every agent.
        assertSendParity("member", carol)

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun participantToken_neverWrites_emptyEvenWithCanWriteGrant() = testApplication {
        val db = Files.createTempFile("cyp779-pt", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        // A participant granted canRead+canWrite on frontend's spoke — but CYP-297 L3 rejects participant SENDS
        // (403) regardless of canWrite, so it MUST NOT appear writable (else enable-then-403 returns). Inherited
        // for free because writableAgents derives from writableChannels, which already excludes participants.
        val ptRaw = ptStore.mint("byo-consumer")
        client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"po-frontend","agentId":"participant:byo-consumer","canRead":true,"canWrite":true}""")
        }
        val participant = "Authorization" to "Bearer $ptRaw"
        assertTrue(writableAgents(participant).isEmpty(), "a participant NEVER writes (CYP-297 L3) → empty even WITH a canWrite grant")
        assertSendParity("participant", participant) // holds only because writable is empty AND every send is 403
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun participantClaimingSubjectPo_cannotWriteOpPo_unspoofable() = testApplication {
        val db = Files.createTempFile("cyp-tokensubj", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        // Reviewer tooth (CYP-787 op-po unspoofability, explicit): a participant token whose SUBJECT is literally
        // "po" resolves to the RESERVED principal `participant:po` (CYP-297) — NEVER the PO AGENT "po". So a caller
        // cannot impersonate the PO by naming its subject "po": participant:po is not the op-po member "po", and
        // CYP-297 Layer-3 rejects participant sends regardless of any grant. Structurally already fail-closed;
        // this pins it so a future change can't silently open op-po to a subject-"po" participant.
        val ptRaw = ptStore.mint("po")
        val participant = "Authorization" to "Bearer $ptRaw"
        // Positive control: the operator CAN write op-po (201) → the 403 below is the participant block, not a
        // channel that is closed to everyone (non-vacuous).
        assertEquals(HttpStatusCode.Created, client.post("/api/channels/op-po/messages") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody("""{"body":"op-task"}""")
        }.status, "positive control: the operator may write op-po")
        // Even after an operator GRANTS participant:po canWrite on op-po, CYP-297 L3 still rejects the send —
        // a subject-"po" participant can never become the PO on its own channel.
        client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"op-po","agentId":"participant:po","canRead":true,"canWrite":true}""")
        }
        val spoof = client.post("/api/channels/op-po/messages") {
            header(participant.first, participant.second); contentType(ContentType.Application.Json); setBody("""{"body":"spoof-the-po"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, spoof.status, "participant:po (subject 'po') cannot write the PO's op-po even WITH a canWrite grant — unspoofable (CYP-297 L3)")
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun operator_mayWriteEveryWorker_andThePo_viaOpPo() = testApplication {
        val db = Files.createTempFile("cyp779-op", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        val writable = writableAgents(op).toSet()
        // CYP-787: the operator is a member-of-all with canWrite → every WORKER spoke AND the PO's new op-po
        // spoke are writable. This FLIPS the pre-787 exclusion: CYP-98 "PO=hub, never a task target" is now
        // narrowly amended to the ONE operator-inbound edge (op-po) — workers still can't task the PO (below).
        assertEquals(setOf("frontend", "backend", "po"), writable, "operator may write every worker AND the PO (op-po)")
        assertTrue("po" in writable, "CYP-787: the PO gained its op-po spoke → now a send target for the operator")
        assertSendParity("operator", op)
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun writable_requiresACredential_401() = testApplication {
        val db = Files.createTempFile("cyp779-gate", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/agents/writable").status, "the writable read is participant-gated like /agents")
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
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),
                AgentConfig("frontend", "FE", Role.WORKER),
                AgentConfig("backend", "BE", Role.WORKER),
            ),
        )
        val secrets = Secrets(emptyMap(), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp779-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
