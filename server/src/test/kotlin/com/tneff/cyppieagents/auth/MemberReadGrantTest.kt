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
import io.ktor.client.request.header
import io.ktor.client.request.get
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
 * CYP-186 (S18 kick-off / BE2) — the **MEMBER read-grant AC**, load-bearing. A human MEMBER session:
 * (1) **comm-read is ACL-`canRead`-filtered by identityId** — fail-closed EMPTY with no grant, and after an
 * OPERATOR grants read on ONE channel, the MEMBER sees ONLY that channel (never blanket); the MEMBER can NOT
 * post (read-only); (2) **config apikey = masked only**, never the raw key; (3) **event-log readable** at the
 * MEMBER tier (secret-free metadata). The "only-X" + "empty-pre-grant" assertions ARE the anti-blanket teeth:
 * a resolver that leaked all channels would red them.
 */
class MemberReadGrantTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val memberId = "member-id"

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true), // first-verified → OPERATOR
                "sess-member" to ResolvedIdentity(memberId, verified = true),   // later → MEMBER
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    /** alice verifies first via /me → bootstraps the human OPERATOR, so the later member is a true MEMBER. */
    private suspend fun ApplicationTestBuilder.bootstrapOperatorThenMember() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-member") }
    }

    @Test
    fun commRead_failClosedEmpty_thenOnlyGrantedChannel_andReadOnly() = testApplication {
        val db = Files.createTempFile("be2-comm", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember()

        fun member() = "sess-member"
        // 1. No grant → the MEMBER sees NO channels (fail-closed; a blanket resolver would leak all here).
        val before = channelIds(client.get("/api/channels") { header("X-Session-Token", member()) }.bodyAsText())
        assertTrue(before.isEmpty(), "MEMBER with no grant must see zero channels (fail-closed) — saw $before")

        // 2. Discover a real channel (as operator) and grant the MEMBER read-only on exactly that one.
        val allChannels = channelIds(client.get("/api/channels") { header("Authorization", "Bearer $opToken") }.bodyAsText())
        assertTrue(allChannels.size >= 2, "fixture needs ≥2 channels to prove 'only the granted one' — had $allChannels")
        val granted = allChannels.first()
        val put = client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"$granted","agentId":"$memberId","canRead":true,"canWrite":false}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        // 3. Now the MEMBER sees ONLY the granted channel — never the others (no blanket).
        val after = channelIds(client.get("/api/channels") { header("X-Session-Token", member()) }.bodyAsText())
        assertEquals(listOf(granted), after, "MEMBER must see ONLY the granted channel, not $allChannels")

        // 4. Read-only: the MEMBER can NOT post (the send path is token-only → 401).
        val send = client.post("/api/channels/$granted/messages") {
            header("X-Session-Token", member()); contentType(ContentType.Application.Json); setBody("""{"body":"hi"}""")
        }
        assertFalse(send.status.value in 200..299, "MEMBER must NOT be able to post (read-only) — got ${send.status}")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun configApiKey_memberSeesMaskedOnly_neverRawKey() = testApplication {
        val db = Files.createTempFile("be2-cfg", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember()

        val raw = "sk-raw-SECRETMIDDLE-1234"
        val set = client.put("/api/config/apikey") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody("""{"apiKey":"$raw"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)

        val view = client.get("/api/config/apikey") { header("X-Session-Token", "sess-member") }
        assertEquals(HttpStatusCode.OK, view.status, "MEMBER may read the MASKED apikey status")
        val body = view.bodyAsText()
        assertFalse(body.contains("SECRETMIDDLE"), "the raw key must NEVER egress to a MEMBER — body: $body")
        assertFalse(body.contains(raw), "the full raw key must NEVER egress to a MEMBER")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun eventLog_readableAtMemberTier() = testApplication {
        val db = Files.createTempFile("be2-ev", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember()

        // Secret-free metadata → readable at the MEMBER tier (was OPERATOR-only). A non-operator's `?projectId`
        // override is IGNORED server-side (forced active) so it can't enumerate other projects.
        val r = client.get("/api/events?projectId=all") { header("X-Session-Token", "sess-member") }
        assertEquals(HttpStatusCode.OK, r.status, "the event-log is MEMBER-readable metadata")

        store.close(); Files.deleteIfExists(db)
    }

    private fun channelIds(body: String): List<String> =
        json.parseToJsonElement(body).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    // ---- fake boot (mirrors MemberTier403MatrixTest.bootFake) ----

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
                AgentConfig("backend", "BE", Role.WORKER),
                AgentConfig("frontend", "FE", Role.WORKER),
            ),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend", "tok-frontend" to "frontend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("be2-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
