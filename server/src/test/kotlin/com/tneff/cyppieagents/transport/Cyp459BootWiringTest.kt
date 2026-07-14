package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.SecretStore
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * CYP-459 (S3) — the boot-wiring of the remote reverse-tunnel connector is **opt-in-off** (parity with the S-C
 * hub-identity / CYP-476 device-store wiring): a default boot yields [InertRelayConnector] (no dial, current server
 * unchanged), and [buildRemoteTransport] builds the live [NoiseRelayConnector] (with the RR3 gate) **only** when the
 * Phase-2-Remote-GO gate env + local-hub custody + the CP-pin config are ALL present — else **fail-closed to Inert**.
 */
class Cyp459BootWiringTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clients = mutableListOf<HttpClient>()
    @AfterTest fun tearDown() { clients.forEach { it.close() }; scope.cancel() }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }
    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }
    private fun config() = PlatformConfig(
        RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
    )
    private fun secrets() = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
    private fun gitRoot() = Files.createTempDirectory("cyp459-boot").toFile()

    private fun secretStoreWithDhKey(): SecretStore = object : SecretStore {
        private val dh = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        override fun put(name: String, secret: String) {}
        override fun get(name: String): String? = if (name == HubIdentityProvisioner.DH_KEY) dh else null
        override fun contains(name: String): Boolean = name == HubIdentityProvisioner.DH_KEY
        override fun delete(name: String) {}
        override fun names(): Set<String> = setOf(HubIdentityProvisioner.DH_KEY)
    }
    private fun hubIdentity() = HubIdentity("hub_x", "sig", "dh", 0)
    private fun completeEnv() = mapOf(
        "CYPPIE_REMOTE_RELAY_URL" to "wss://relay.example/rzv",
        "CYPPIE_OPERATOR_ID" to "op-1",
        "CYPPIE_CP_ISSUER" to "cp-issuer",
        "CYPPIE_CP_KID" to "kid1",
        "CYPPIE_CP_PUBKEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        "CYPPIE_OPERATOR_RP_ID" to "hub.example",
        // CYP-521: the hub-dial rendezvous id now comes from the CP register (not a static env) → needs the CP URL + operator bearer.
        "CYPPIE_CP_URL" to "https://cp.example",
        "CYPPIE_CP_OPERATOR_TOKEN" to "op-bearer",
    )

    @Test
    fun defaultBoot_remoteTransport_isInert() {
        val booted = BootOrchestrator(config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope).boot()
        assertSame(InertRelayConnector, booted.remoteTransport, "opt-in-off: default boot never wires a live relay dial")
    }

    @Test
    fun buildRemoteTransport_gateEnvOff_isInert() {
        val c = buildRemoteTransport(8787, hubIdentity(), secretStoreWithDhKey(), InMemoryOperatorDeviceStore(), scope, env = { null })
        assertSame(InertRelayConnector, c, "no CYPPIE_REMOTE_RELAY_URL → INERT")
    }

    @Test
    fun buildRemoteTransport_complete_buildsLiveNResponderManager() {
        val env = completeEnv()
        val c = buildRemoteTransport(
            loopbackPort = 8787,
            hubIdentity = hubIdentity(),
            hubSecretStore = secretStoreWithDhKey(),
            operatorDeviceStore = InMemoryOperatorDeviceStore(),
            scope = scope,
            env = { env[it] },
            httpClientFactory = { HttpClient(CIO) { install(WebSockets) }.also { clients += it } },
        )
        // CYP-536: the live path now builds the N-concurrent responder manager (each per-id responder is a
        // NoiseRelayConnector with the RR3 gate; the manager fans them over the epoch-derived rendezvous set).
        assertIs<ConcurrentRelayResponderManager>(c, "gate + custody + CP-pin config complete → the live N-responder manager")
    }

    @Test
    fun buildRemoteTransport_missingCustody_failsClosedToInert() {
        val env = completeEnv()
        // relayUrl + full env, but NO local-hub custody (hubIdentity/secretStore/deviceStore null) → Inert.
        val c = buildRemoteTransport(8787, null, null, null, scope, env = { env[it] })
        assertSame(InertRelayConnector, c, "missing custody fails closed to Inert (never a half-configured dial)")
    }

    @Test
    fun buildRemoteTransport_incompleteConfig_failsClosedToInert() {
        // gate on + custody present, but a REQUIRED CP-pin var (operator id) is missing → Inert.
        val env = completeEnv() - "CYPPIE_OPERATOR_ID"
        val c = buildRemoteTransport(8787, hubIdentity(), secretStoreWithDhKey(), InMemoryOperatorDeviceStore(), scope, env = { env[it] })
        assertSame(InertRelayConnector, c, "an incomplete CP-pin config fails closed to Inert")
    }

    @Test
    fun buildRemoteTransport_missingCpUrl_failsClosedToInert() {
        // CYP-521: the dial-side rendezvous register needs the CP URL — absent → Inert (no static-env fallback).
        val env = completeEnv() - "CYPPIE_CP_URL"
        val c = buildRemoteTransport(8787, hubIdentity(), secretStoreWithDhKey(), InMemoryOperatorDeviceStore(), scope, env = { env[it] })
        assertSame(InertRelayConnector, c, "no CYPPIE_CP_URL (register target) → fails closed to Inert")
    }

    @Test
    fun buildRemoteTransport_missingCpOperatorToken_failsClosedToInert() {
        val env = completeEnv() - "CYPPIE_CP_OPERATOR_TOKEN"
        val c = buildRemoteTransport(8787, hubIdentity(), secretStoreWithDhKey(), InMemoryOperatorDeviceStore(), scope, env = { env[it] })
        assertSame(InertRelayConnector, c, "no CYPPIE_CP_OPERATOR_TOKEN (register auth) → fails closed to Inert")
    }
}
