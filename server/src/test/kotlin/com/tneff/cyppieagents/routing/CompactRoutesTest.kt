package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.KratosSettingsClient
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-329 — `POST /api/compact/config` range-validation is FAIL-CLOSED: an out-of-range timing is rejected 400
 * with NO partial apply (the previously-persisted config is untouched), and a valid change is persisted and
 * reflected by `GET /api/compact/status`. Validation uses the single-sourced [CompactConfig.timingBoundsError].
 */
class CompactRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val opToken = "tok-op"
    private val op get() = "Authorization" to "Bearer $opToken"

    private fun authDeps() = AuthDeps(tokens = TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true))

    private suspend fun ApplicationTestBuilder.postConfig(body: String) = client.post("/api/compact/config") {
        header(op.first, op.second); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun ApplicationTestBuilder.statusStagger(): Long =
        json.parseToJsonElement(client.get("/api/compact/status") { header(op.first, op.second) }.bodyAsText())
            .jsonObject["staggerMs"]!!.jsonPrimitive.long

    @Test
    fun validTiming_persists200_andStatusReflectsIt() = testApplication {
        application { installPlatform(bootFake(), authDeps(), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        val ok = postConfig("""{"allowed":true,"thresholdTokens":500000,"staggerMs":60000,"roundGapMs":90000,"roundWindowMs":600000}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(60_000L, statusStagger(), "a valid timing change is persisted and shown by /status")
    }

    @Test
    fun outOfRangeTiming_rejected400_noPartialApply() = testApplication {
        application { installPlatform(bootFake(), authDeps(), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        // First persist a valid value so we can prove the rejected write does NOT overwrite it.
        assertEquals(HttpStatusCode.OK, postConfig("""{"allowed":true,"staggerMs":60000,"roundGapMs":120000,"roundWindowMs":600000}""").status)
        assertEquals(60_000L, statusStagger())

        // staggerMs 10_000 < STAGGER_MIN_MS (30_000) → 400 fail-closed.
        val bad = postConfig("""{"allowed":true,"staggerMs":10000,"roundGapMs":120000,"roundWindowMs":600000}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status, "out-of-range stagger is rejected")

        // The rejected write left the persisted config UNCHANGED (no partial apply).
        assertEquals(60_000L, statusStagger(), "the 400 must NOT have overwritten the previously-valid config")
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
            ),
        )
        val secrets = Secrets(emptyMap(), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp329-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
