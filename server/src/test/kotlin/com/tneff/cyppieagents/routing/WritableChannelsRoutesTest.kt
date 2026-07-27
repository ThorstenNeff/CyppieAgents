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
 * CYP-273 — `GET /api/channels/writable` (the composer-enable seam). Returns the channel ids the resolved caller
 * may currently WRITE — a **subset of readable**, computed via the SAME `AclMatrix.canWrite` the POST-send
 * enforces (single-source: the endpoint's answer MATCHES the actual send outcome). Content-free (channel ids the
 * caller already sees via `/api/channels`); the server-403 at the chokepoint stays the authority.
 */
class WritableChannelsRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val memberId = "carol-mem"
    private val ptStore = ParticipantTokenStore { 1_000L } // CYP-273×297: a participant caller for the parity check

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true),
        idp = FakeIdentityProvider(mapOf("sess-carol" to ResolvedIdentity(memberId, verified = true))),
        roles = store, nowMs = { 1_000L },
        participantTokens = ptStore,
    )

    private suspend fun ApplicationTestBuilder.readableChannels(bearerHeader: Pair<String, String>): List<String> =
        json.parseToJsonElement(client.get("/api/channels") { header(bearerHeader.first, bearerHeader.second) }.bodyAsText())
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.writableChannels(bearerHeader: Pair<String, String>): List<String> =
        json.parseToJsonElement(client.get("/api/channels/writable") { header(bearerHeader.first, bearerHeader.second) }.bodyAsText())
            .jsonArray.map { it.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.grant(ch: String, canRead: Boolean, canWrite: Boolean) = client.put("/api/acl") {
        header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
        setBody("""{"channelId":"$ch","agentId":"$memberId","canRead":$canRead,"canWrite":$canWrite}""")
    }

    private suspend fun ApplicationTestBuilder.send(ch: String, hdr: Pair<String, String>) = client.post("/api/channels/$ch/messages") {
        header(hdr.first, hdr.second); contentType(ContentType.Application.Json); setBody("""{"body":"hi"}""")
    }

    /**
     * CYP-273 (×297) — the seam's CORE invariant, made caller-GENERAL so it bites the participant case too (the
     * old assertion was participant-blind → green while the invariant already broke): for EVERY readable channel,
     * `id ∈ writable` ⟺ the actual POST-send is 201 (else the deny is 403). `writable ⊆ readable` throughout.
     */
    private suspend fun ApplicationTestBuilder.assertSendParity(label: String, hdr: Pair<String, String>) {
        val readable = readableChannels(hdr)
        val writable = writableChannels(hdr).toSet()
        assertTrue(writable.all { it in readable }, "$label: writable ⊄ readable — writable=$writable readable=$readable")
        for (ch in readable) {
            val sent201 = send(ch, hdr).status == HttpStatusCode.Created
            assertEquals(ch in writable, sent201, "$label: parity break on $ch — inWritable=${ch in writable} sent201=$sent201")
        }
    }

    private val op get() = "Authorization" to "Bearer $opToken"
    private val carol get() = "X-Session-Token" to "sess-carol"

    @Test
    fun writable_isReadableSubset_filteredByCanWrite_andMatchesSendOutcome() = testApplication {
        val db = Files.createTempFile("cyp273", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") } // verify → MEMBER

        // Before any grant, the MEMBER is a member of nothing → readable AND writable both empty (fail-closed).
        assertTrue(readableChannels(carol).isEmpty(), "MEMBER sees no channel before any grant")
        assertTrue(writableChannels(carol).isEmpty(), "MEMBER may write nothing before any grant")

        // The operator sees the spokes; take two distinct channels.
        val allChannels = readableChannels(op)
        assertTrue(allChannels.size >= 2, "need >=2 spokes for the read-only-vs-writable split — had $allChannels")
        val chWrite = allChannels[0]
        val chReadOnly = allChannels[1]

        // Grant the MEMBER canWrite on one, canRead-ONLY on the other.
        assertEquals(HttpStatusCode.OK, grant(chWrite, canRead = true, canWrite = true).status)
        assertEquals(HttpStatusCode.OK, grant(chReadOnly, canRead = true, canWrite = false).status)

        val readable = readableChannels(carol).toSet()
        val writable = writableChannels(carol).toSet()

        // T1 — writable ⊆ readable, and it is EXACTLY the canWrite-granted channel (the canRead-only one is omitted).
        assertTrue(writable.all { it in readable }, "writable must be a subset of readable — writable=$writable readable=$readable")
        assertTrue(chWrite in writable, "the canWrite-granted channel is writable")
        assertFalse(chReadOnly in writable, "the canRead-ONLY channel must NOT be writable (this is the seam)")
        assertTrue(chReadOnly in readable, "the canRead-only channel is still readable")

        // T2 — single-source parity for the MEMBER: `id in writable` ⟺ POST 201 (else 403).
        assertSendParity("member", carol)

        // T2b — the PARTICIPANT case (CYP-273×297): a participant is granted canRead+canWrite on chWrite, but
        // CYP-297 Layer 3 rejects participant SENDS (403) regardless of canWrite. So `writableChannels` MUST
        // exclude participants entirely (isParticipant → empty) — else the channel shows writable=true while the
        // send is 403, re-introducing the enable-then-403 UX the seam eliminates. assertSendParity bites this.
        val ptRaw = ptStore.mint("byo-consumer") // principal = participant:byo-consumer
        val grantPt = client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"$chWrite","agentId":"participant:byo-consumer","canRead":true,"canWrite":true}""")
        }
        assertEquals(HttpStatusCode.OK, grantPt.status)
        val participant = "Authorization" to "Bearer $ptRaw"
        assertTrue(writableChannels(participant).isEmpty(), "a participant NEVER writes (CYP-297 L3) → its writable set is empty even WITH a canWrite grant")
        assertSendParity("participant", participant) // ⟺ holds only because writable is empty AND every send is 403

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun writable_requiresACredential_401() = testApplication {
        val db = Files.createTempFile("cyp273-gate", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/channels/writable").status, "the writable read is participant-gated like /channels")
    }

    @Test
    fun operator_mayWriteEveryReadableChannel() = testApplication {
        val db = Files.createTempFile("cyp273-op", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        // The operator is a member-of-all with canWrite=true → its writable set == its readable set.
        assertEquals(readableChannels(op).toSet(), writableChannels(op).toSet(), "operator may write every channel it can read")
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
        val gitRoot = Files.createTempDirectory("cyp273-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
