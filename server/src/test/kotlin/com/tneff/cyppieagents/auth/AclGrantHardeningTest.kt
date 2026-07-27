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
 * CYP-188 — `PUT /api/acl` grant hardening at the `HubState.setAcl` chokepoint:
 *  - **fail-fast:** a grant for a channelId with NO `Channel` object → **404** (was a silent orphan entry that
 *    `GET /api/acl` showed yet every later send/read 403'd — deploy's misleading live symptom);
 *  - **projectId single-source:** `setAcl` stamps `activeProjectId` and IGNORES the client's `projectId`, so a
 *    grant lands in the active project's scope (fixes the latent non-default-project scoping drop) and no
 *    cross-project entry can be injected via this path;
 *  - **regression guard:** on a REAL channel the grant syncs the grantee into `Channel.members` → send 201.
 */
class AclGrantHardeningTest {

    private val opToken = "tok-op"
    private val json = Json { ignoreUnknownKeys = true }
    private val memberId = "carol-mem"

    private fun deps(store: SqliteRoleStore) = AuthDeps(
        TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true),
        FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true),
                "sess-carol" to ResolvedIdentity(memberId, verified = true),
            ),
        ),
        store, { 1_000L },
    )

    private suspend fun ApplicationTestBuilder.setup(): String {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") }
        return json.parseToJsonElement(client.get("/api/channels") { header("Authorization", "Bearer $opToken") }.bodyAsText())
            .jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.grant(body: String) = client.put("/api/acl") {
        header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun ApplicationTestBuilder.membersOf(ch: String): List<String> =
        json.parseToJsonElement(client.get("/api/channels") { header("Authorization", "Bearer $opToken") }.bodyAsText())
            .jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == ch }
            .jsonObject["members"]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun ApplicationTestBuilder.memberSend(ch: String) = client.post("/api/channels/$ch/messages") {
        header("X-Session-Token", "sess-carol"); contentType(ContentType.Application.Json); setBody("""{"body":"hi"}""")
    }

    @Test
    fun ghostChannel_grantRoute404_butSendRouteStaysUniform403_separated() = testApplication {
        val db = Files.createTempFile("hard-ghost", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake("default"), deps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); setup()

        // GRANT route (OPERATOR-facing): a grant for a channelId with no Channel object → 404. Distinguishable-
        // ness is fine here — an operator already enumerates channels via GET /api/channels, so this is NOT a
        // member-facing oracle. And no orphan entry is persisted (the misleading silent-200-then-403 is closed).
        val grantResp = grant("""{"channelId":"ghost-no-object","agentId":"$memberId","canRead":true,"canWrite":true}""")
        assertEquals(HttpStatusCode.NotFound, grantResp.status, "grant for a channelId with no Channel object → 404 (not a silent 200)")
        val acl = client.get("/api/acl") { header("Authorization", "Bearer $opToken") }.bodyAsText()
        assertTrue(!acl.contains("ghost-no-object"), "the rejected grant must NOT persist an orphan entry — acl: $acl")

        // SEND route (MEMBER-facing) is a SEPARATE path — the ghost fail-fast is grant-only and does NOT touch it:
        // a member posting to a non-existent channel stays a UNIFORM 403 (no 404-vs-403 channel-existence tell).
        assertEquals(HttpStatusCode.Forbidden, memberSend("ghost-no-object").status,
            "send to a non-existent channel stays a uniform member-facing 403 — the grant fail-fast must not leak into the send route")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun realChannel_grant_syncsMemberAndSends201() = testApplication {
        val db = Files.createTempFile("hard-real", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake("default"), deps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); val ch = setup()

        assertTrue(memberId !in membersOf(ch), "grantee not a member before the grant")
        assertEquals(HttpStatusCode.OK, grant("""{"channelId":"$ch","agentId":"$memberId","canRead":true,"canWrite":true}""").status)
        // The load-bearing regression guard: the grant SYNCS the human into Channel.members via the HTTP route.
        assertTrue(memberId in membersOf(ch), "the grant must sync the grantee into Channel.members (via the route)")
        assertEquals(HttpStatusCode.Created, memberSend(ch).status, "the granted human sends 201 on the real channel")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun projectId_isServerStamped_clientValueIgnored() = testApplication {
        val db = Files.createTempFile("hard-proj", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake("default"), deps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); val ch = setup()

        // The client tries to inject a foreign projectId; setAcl must ignore it and stamp the active one.
        grant("""{"channelId":"$ch","agentId":"$memberId","canRead":true,"canWrite":true,"projectId":"other-proj"}""")
        val stored = json.parseToJsonElement(client.get("/api/acl") { header("Authorization", "Bearer $opToken") }.bodyAsText())
            .jsonArray.first { it.jsonObject["agentId"]!!.jsonPrimitive.content == memberId }
            .jsonObject["projectId"]!!.jsonPrimitive.content
        assertEquals("default", stored, "the stored projectId must be the active one, NOT the client-injected 'other-proj'")
        assertEquals(HttpStatusCode.Created, memberSend(ch).status, "the active-stamped grant works end-to-end")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun nonDefaultProject_grantWithoutProjectId_stampedActive_send201() = testApplication {
        val db = Files.createTempFile("hard-nd", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake("proj-A"), deps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); val ch = setup()

        assertEquals(HttpStatusCode.Forbidden, memberSend(ch).status, "no grant → 403")
        // Client sends no projectId (→ default); the fix stamps proj-A so the entry is in-scope (was 403 before).
        assertEquals(HttpStatusCode.OK, grant("""{"channelId":"$ch","agentId":"$memberId","canRead":true,"canWrite":true}""").status)
        assertEquals(HttpStatusCode.Created, memberSend(ch).status, "a stamped grant in a non-default project → 201")

        store.close(); Files.deleteIfExists(db)
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(projectId: String): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            projectId = projectId,
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("acl-hard-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
