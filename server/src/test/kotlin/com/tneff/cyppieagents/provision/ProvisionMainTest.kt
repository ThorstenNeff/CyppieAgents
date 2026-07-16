package com.tneff.cyppieagents.provision

import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretAad
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.model.Role
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-628 — the provisioning entrypoint must mint a master key the REAL crypto accepts and a config the REAL boot
 * loads. These are the load-bearing correctness teeth (a wrong keyset format or an invalid config = a hub that
 * won't boot on the customer's host).
 */
class ProvisionMainTest {

    private fun provision(dir: File, secretsOut: File, extra: List<String> = emptyList()) {
        main(
            (listOf(
                "--data-dir", dir.absolutePath,
                "--host", "127.0.0.1", "--port", "8787", "--tunnel-port", "8786",
                "--repo", "file:///srv/repo", "--secrets-out", secretsOut.absolutePath,
            ) + extra).toTypedArray(),
        )
    }

    private fun readSecrets(f: File): Map<String, String> =
        f.readLines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun generatedMasterKey_isAcceptedByTheRealCipher_encryptDecryptRoundtrips() {
        val dir = Files.createTempDirectory("cyp628").toFile()
        val secrets = File(dir, "secrets.env")
        provision(dir, secrets)

        val masterKey = readSecrets(secrets).getValue("CYPPIE_MASTER_KEY")
        // Build the REAL cipher from the generated keyset (exactly as SqliteSecretStore does) and roundtrip a secret.
        val cipher = SecretCipherFactory.single(1, MasterKeySource.Box(masterKey))
        val aad = SecretAad(storeKey = "project_config", projectId = "default", field = "apiKey")
        val enc = cipher.encrypt("sk-ant-SECRET-api-key", aad)
        assertEquals("sk-ant-SECRET-api-key", cipher.decrypt(enc, aad), "the provisioned CYPPIE_MASTER_KEY is a valid Tink keyset the real SecretStore crypto accepts")
    }

    @Test
    fun masterKey_isSingleLine_envSafe() {
        // Regression: the serialized Tink keyset is pretty-printed JSON; a multi-line CYPPIE_MASTER_KEY breaks env
        // transport (WinSW <env> / a service-account env var / a KEY=VALUE file) — the wizard's provisioning would
        // corrupt it on the newline. It must be minified to ONE line (and still parse — pinned by the roundtrip above).
        val dir = Files.createTempDirectory("cyp628").toFile()
        val secrets = File(dir, "secrets.env")
        provision(dir, secrets)
        val masterKey = readSecrets(secrets).getValue("CYPPIE_MASTER_KEY")
        assertEquals(1, masterKey.lines().count { it.isNotEmpty() }, "CYPPIE_MASTER_KEY must be a single line (env-safe)")
        assertTrue('\n' !in masterKey && '\r' !in masterKey, "no embedded newlines in the master key")
    }

    @Test
    fun generatedConfig_loadsAsPlatformConfig_withExactlyOnePo() {
        val dir = Files.createTempDirectory("cyp628").toFile()
        provision(dir, File(dir, "secrets.env"))

        val cfg = PlatformConfig.load(File(dir, "platform.config.json")) // throws on a bad shape / the 1-PO invariant
        assertEquals(1, cfg.agents.count { it.role == Role.PO }, "exactly one PO agent (boot invariant)")
        assertEquals("127.0.0.1", cfg.hub.host)
        assertEquals(8787, cfg.hub.port)
        assertEquals("file:///srv/repo", cfg.repo.url)
    }

    @Test
    fun requiredTokens_areMinted_nonBlank_andDistinct() {
        val dir = Files.createTempDirectory("cyp628").toFile()
        val secrets = File(dir, "secrets.env")
        provision(dir, secrets)

        val s = readSecrets(secrets)
        val op = s.getValue("OPERATOR_TOKEN"); val po = s.getValue("HUB_TOKEN_PO")
        assertTrue(op.isNotBlank() && po.isNotBlank(), "both required boot tokens are minted")
        assertNotEquals(op, po, "the operator token and the agent token are independent")
    }

    @Test
    fun reRun_mintsFreshSecrets_neverReusesAKey() {
        val d1 = Files.createTempDirectory("cyp628").toFile(); val s1 = File(d1, "s.env")
        val d2 = Files.createTempDirectory("cyp628").toFile(); val s2 = File(d2, "s.env")
        provision(d1, s1); provision(d2, s2)
        val a = readSecrets(s1); val b = readSecrets(s2)
        assertNotEquals(a.getValue("CYPPIE_MASTER_KEY"), b.getValue("CYPPIE_MASTER_KEY"), "each install mints a fresh master key")
        assertNotEquals(a.getValue("OPERATOR_TOKEN"), b.getValue("OPERATOR_TOKEN"), "each install mints fresh tokens")
    }
}
