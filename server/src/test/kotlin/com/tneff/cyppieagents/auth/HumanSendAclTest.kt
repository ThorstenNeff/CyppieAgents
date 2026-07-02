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
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-188 P2b-iii (option a) — the **human-send** ACL. A verified human MEMBER session may post ONLY where an
 * OPERATOR granted per-channel `canWrite:true` (deny-without-grant 403 / allow-with-grant 201), the deny is a
 * **uniform 403** (no channel-existence tell), and the `canWrite` gate lives at the single `postAsAgent`
 * write chokepoint (no `/ws/comm` frame can bypass it → send stays REST-only).
 */
class HumanSendAclTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val memberId = "carol-mem"

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true), // first-verified → OPERATOR
                "sess-carol" to ResolvedIdentity(memberId, verified = true),    // later → MEMBER
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    private suspend fun ApplicationTestBuilder.bootstrap() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") }
    }

    private suspend fun ApplicationTestBuilder.aChannel(): String =
        json.parseToJsonElement(client.get("/api/channels") { header("Authorization", "Bearer $opToken") }.bodyAsText())
            .jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.memberSend(channel: String) = client.post("/api/channels/$channel/messages") {
        header("X-Session-Token", "sess-carol"); contentType(ContentType.Application.Json); setBody("""{"body":"hi"}""")
    }

    @Test
    fun member_denyWithoutGrant_allowWithCanWriteGrant() = testApplication {
        val db = Files.createTempFile("cyp188-send", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrap()
        val ch = aChannel()

        // No write grant → 403 (the session IS admitted to the gate; canWrite at the chokepoint denies).
        assertEquals(HttpStatusCode.Forbidden, memberSend(ch).status, "MEMBER with no canWrite grant must be 403")

        // Operator grants per-channel canWrite:true → the MEMBER may now post.
        val grant = client.put("/api/acl") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"channelId":"$ch","agentId":"$memberId","canRead":true,"canWrite":true}""")
        }
        assertEquals(HttpStatusCode.OK, grant.status)
        assertEquals(HttpStatusCode.Created, memberSend(ch).status, "MEMBER WITH a canWrite:true grant may post (201)")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun sendDeny_isUniform403_noChannelExistenceTell() = testApplication {
        val db = Files.createTempFile("cyp188-uni", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrap()
        val existing = aChannel() // a real channel, but the MEMBER has NO grant

        val toExisting = memberSend(existing).status
        val toNonexistent = memberSend("does-not-exist-xyz").status
        // Both must be an identical 403 — a 404-vs-403 split would let a human enumerate which channels exist.
        assertEquals(HttpStatusCode.Forbidden, toExisting, "no-grant on an existing channel → 403")
        assertEquals(HttpStatusCode.Forbidden, toNonexistent, "a non-existent channel → 403 too (not 404)")
        assertEquals(toExisting, toNonexistent, "the send deny must be uniform (no channel-existence tell)")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun wsComm_frame_cannotPost_sendStaysRestOnlyThroughChokepoint() = testApplication {
        val db = Files.createTempFile("cyp188-ws", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrap()
        val ch = aChannel()

        suspend fun count() = json.parseToJsonElement(
            client.get("/api/channels/$ch/messages") { header("Authorization", "Bearer $opToken") }.bodyAsText(),
        ).jsonArray.size
        val before = count()

        // Connect /ws/comm (operator) and push a message-SHAPED frame; the socket only reads Subscribe frames, so
        // it can NEVER post — the ONLY write path is REST → postAsAgent (the single canWrite chokepoint).
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/ws/comm?token=$opToken") {
            send(Frame.Text("""{"body":"injected-via-ws"}"""))
            delay(300)
        }
        assertEquals(before, count(), "a /ws/comm frame must NOT post a message — send is REST-only via postAsAgent")

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
        val gitRoot = Files.createTempDirectory("cyp188-send-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
