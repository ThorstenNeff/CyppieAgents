package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-137 / E2.1 — the provider (tool) axis on the `Agent` DTO + connector contract.
 *  - additive-nullable on the wire (old payloads still decode — the mutation guard),
 *  - declared by every connector and surfaced in `GET /api/agents`,
 *  - identity stays provider-agnostic.
 */
class ProviderDtoTest {

    @Test
    fun oldPayloadWithoutProviderStillDeserializes() {
        // A pre-CYP-137 Agent payload carries no `provider` field — it MUST still decode (additive-nullable).
        // MUTATION: make Agent.provider required (non-null) → this decode throws → reddens (wire-break guard).
        val json = """{"id":"po","name":"Product Owner","role":"PO","worktree":"po"}"""
        val agent = CommJson.decodeFromString<Agent>(json)
        assertEquals("po", agent.id)
        assertNull(agent.provider, "absent provider decodes to null; identity is provider-agnostic")
    }

    @Test
    fun agentProviderRoundTrips() {
        val agent = Agent("po", "PO", Role.PO, "po", provider = ProviderInfo("claude", "Claude"))
        val decoded = CommJson.decodeFromString<Agent>(CommJson.encodeToString(Agent.serializer(), agent))
        assertEquals(ProviderInfo("claude", "Claude"), decoded.provider)
    }

    // --- GET /api/agents surfaces the provider from the REAL connector (the AC) ---

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): com.tneff.cyppieagents.boot.BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("provider-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }

    @Test
    fun getAgentsSurfacesProviderFromTheConnector() = testApplication {
        val booted = bootFake() // boots the REAL ClaudeCodeConnector (provider = Claude) with a fake process
        application { installPlatform(booted) }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }

        val agents: List<Agent> = client.get("/api/agents") { bearerAuth("tok-op") }.body() // CC1/CYP-179: roster now gated
        assertTrue(agents.isNotEmpty(), "agents listed")
        assertEquals(
            ProviderInfo.CLAUDE, agents.first { it.id == "backend" }.provider,
            "GET /api/agents surfaces the agent's connector provider",
        )
        assertTrue(agents.all { it.provider == ProviderInfo.CLAUDE }, "every agent surfaces a provider (MVP = Claude)")
    }
}
