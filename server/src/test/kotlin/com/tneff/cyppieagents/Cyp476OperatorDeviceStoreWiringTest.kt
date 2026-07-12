package com.tneff.cyppieagents

import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-476 ② — the opt-in boot-wiring of the durable operator device store. **Off by default** (no S-B custody →
 * `operatorDeviceStore == null` → the current server is unchanged, health 200); **on** when local-hub custody (a
 * SecretStore, `CYPPIE_MASTER_KEY`-gated in prod) is wired → the CYP-472 durable store is constructed (no longer
 * dead code). Parity with the S-C HubIdentity opt-in wiring.
 */
class Cyp476OperatorDeviceStoreWiringTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

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
    private fun gitRoot() = Files.createTempDirectory("cyp476-boot").toFile()

    @Test fun withoutHubSecretStore_operatorDeviceStore_isNull() {
        val booted = BootOrchestrator(config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope).boot()
        assertNull(booted.operatorDeviceStore, "opt-in-off: no S-B custody → no device store (current server unchanged)")
    }

    @Test fun withHubSecretStore_operatorDeviceStore_isWired_andDurable() {
        val dir = Files.createTempDirectory("cyp476-sb")
        val secretStore = SqliteSecretStore(dir.resolve("secrets.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() })
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope,
            hubSecretStore = secretStore,
        ).boot()

        val store = assertNotNull(booted.operatorDeviceStore, "S-B custody wired → the durable store is constructed")
        assertNull(store.enrolled(), "freshly wired: nothing enrolled yet")
        // it IS the durable S-B-backed store: an enroll persists into the SecretStore and reads back.
        store.save(EnrolledOperatorDevice("dev-1", DeviceKeyAlg.ED25519, ByteArray(32)))
        assertNotNull(store.enrolled())
    }
}
