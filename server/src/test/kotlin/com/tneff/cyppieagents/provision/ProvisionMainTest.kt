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

    /**
     * CYP-687 (M1.1) — `--add-remote-agent` seeds the BYOA acceptance's config-declared remote agent by
     * read-modify-existing. The four invariants (each a distinct mutation the gate can red):
     *  (1) PRESERVES existing agents (the PO survives — append-only, never a replace);
     *  (2) TOKEN-MINT — the new agent's HUB_TOKEN is minted;
     *  (3) NO MASTER-KEY TOUCH — the ADD mode never mints a master key (no re-mint → no orphaned SecretStore);
     *  (4) NO ORPHAN — the agent is config-DECLARED (remote=true in the config) ⇒ rehydrated on restart (durable).
     */
    @Test
    fun cyp687_addRemoteAgent_preservesExisting_mintsToken_untouchesMasterKey() {
        val dir = Files.createTempDirectory("cyp687").toFile()
        provision(dir, File(dir, "provision.env")) // base: 1-PO config + master key + tokens

        // ADD a config-declared remote agent (read-modify-existing; separate secrets file, NOT a re-provision).
        val addSecrets = File(dir, "add.env")
        main(arrayOf("--data-dir", dir.absolutePath, "--add-remote-agent", "sidekick", "--secrets-out", addSecrets.absolutePath))

        // (1)+(4) PRESERVES the PO AND ADDS sidekick as a config-DECLARED remote WORKER (durable ⇒ no orphan).
        val cfg = PlatformConfig.load(File(dir, "platform.config.json"))
        assertEquals(1, cfg.agents.count { it.role == Role.PO }, "the existing PO agent is PRESERVED (append-only, not replaced)")
        val sidekick = cfg.agents.singleOrNull { it.id == "sidekick" }
        assertTrue(
            sidekick != null && sidekick.remote && sidekick.role == Role.WORKER,
            "sidekick added as a config-declared remote WORKER (durable ⇒ rehydrated on restart ⇒ no orphaned token)",
        )

        // (2) TOKEN-MINT: the new agent's HUB_TOKEN is minted (non-blank).
        val added = readSecrets(addSecrets)
        assertTrue(added["HUB_TOKEN_SIDEKICK"]?.isNotBlank() == true, "HUB_TOKEN_SIDEKICK is minted")

        // (3) NO MASTER-KEY TOUCH: the ADD mode never mints/writes a master key (no re-mint of the SecretStore's KEK).
        assertTrue("CYPPIE_MASTER_KEY" !in added, "the ADD mode does NOT mint/write a master key")
    }
}
