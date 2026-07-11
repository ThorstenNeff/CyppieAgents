package com.tneff.cyppieagents.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-434 (S-B) teeth. Hermetic (fresh BOX keysets + temp SQLite, no KMS/network). The **mandatory mutation-proven
 * tooth** is [masterKeyMismatch_failsClosedAtOpen]: a store opened over another master key's data must refuse to
 * start (canary), and [get] must propagate a decrypt failure — never swallow it to null/plaintext. Removing either
 * fail-closed check turns a red assertion green. The rest prove custody fail-closed (env + passphrase) and the AAD
 * "cannot-be-relocated" binding at the store layer.
 */
class SecretStoreCustodyTest {

    private fun tempDir(): Path = Files.createTempDirectory("cyp434-secretstore")
    private fun keysetCustody(keyset: String) = MasterKeyCustody { keyset }

    @Test fun roundTrip_put_get_contains_names_delete() {
        val dir = tempDir()
        val keyset = SecretCipherFactory.newBoxKeyset()
        SqliteSecretStore(dir.resolve("s.db"), keysetCustody(keyset)).use { store ->
            assertNull(store.get("hub.signingKey"))
            assertFalse(store.contains("hub.signingKey"))

            store.put("hub.signingKey", "ed25519-priv-XYZ")
            store.put("hub.dhKey", "x25519-priv-ABC")

            assertEquals("ed25519-priv-XYZ", store.get("hub.signingKey"))
            assertTrue(store.contains("hub.signingKey"))
            assertEquals(setOf("hub.signingKey", "hub.dhKey"), store.names()) // canary excluded

            store.delete("hub.signingKey")
            assertNull(store.get("hub.signingKey"))
            assertEquals(setOf("hub.dhKey"), store.names())
        }
    }

    @Test fun secretsPersist_acrossReopen_withSameMasterKey() {
        val dir = tempDir()
        val keyset = SecretCipherFactory.newBoxKeyset()
        val db = dir.resolve("s.db")
        SqliteSecretStore(db, keysetCustody(keyset)).use { it.put("hub.dhKey", "x25519-priv-ABC") }
        SqliteSecretStore(db, keysetCustody(keyset)).use { reopened ->
            assertEquals("x25519-priv-ABC", reopened.get("hub.dhKey"))
        }
    }

    /** ★ MANDATORY TOOTH — a wrong master key must fail closed at open (canary), never expose ciphertext. */
    @Test fun masterKeyMismatch_failsClosedAtOpen() {
        val dir = tempDir()
        val db = dir.resolve("s.db")
        val keyA = SecretCipherFactory.newBoxKeyset()
        SqliteSecretStore(db, keysetCustody(keyA)).use { it.put("hub.signingKey", "ed25519-priv-XYZ") }

        val keyB = SecretCipherFactory.newBoxKeyset() // a DIFFERENT master key over A's data
        assertFailsWith<SecretCipherException> {
            SqliteSecretStore(db, keysetCustody(keyB)) // canary decrypt fails → refuses to start
        }
    }

    /** ★ TOOTH (companion) — [get] propagates a decrypt failure; it must not swallow it into null/plaintext. */
    @Test fun get_onTamperedCiphertext_failsClosed_notNull() {
        val dir = tempDir()
        val db = dir.resolve("s.db")
        val keyset = SecretCipherFactory.newBoxKeyset()
        SqliteSecretStore(db, keysetCustody(keyset)).use { it.put("hub.signingKey", "ed25519-priv-XYZ") }

        corruptCiphertextRow(db, "hub.signingKey") // flip the stored bytes (canary row untouched → open succeeds)

        SqliteSecretStore(db, keysetCustody(keyset)).use { store ->
            assertFailsWith<SecretCipherException> { store.get("hub.signingKey") }
        }
    }

    /** The AAD "cannot-be-relocated" property at the store layer: a ciphertext bound to name A can't decrypt as B. */
    @Test fun ciphertext_cannotBeRelocated_toAnotherName() {
        val dir = tempDir()
        val db = dir.resolve("s.db")
        val keyset = SecretCipherFactory.newBoxKeyset()
        SqliteSecretStore(db, keysetCustody(keyset)).use { it.put("name.a", "secret-a") }

        relocateCiphertext(db, from = "name.a", to = "name.b") // copy A's blob into a row named B

        SqliteSecretStore(db, keysetCustody(keyset)).use { store ->
            assertFailsWith<SecretCipherException> { store.get("name.b") } // AAD(name=b) != AAD(name=a) → fail-closed
        }
    }

    @Test fun envCustody_missingKey_failsClosed() {
        val custody = EnvKeysetMasterKeyCustody(env = { null })
        assertFailsWith<SecretCipherException> { custody.masterKeyset() }
        // and the store built on it refuses to construct
        assertFailsWith<SecretCipherException> {
            SqliteSecretStore(tempDir().resolve("s.db"), custody)
        }
    }

    @Test fun envCustody_present_backsTheStore() {
        val keyset = SecretCipherFactory.newBoxKeyset()
        val custody = EnvKeysetMasterKeyCustody(env = { if (it == "CYPPIE_MASTER_KEY") keyset else null })
        SqliteSecretStore(tempDir().resolve("s.db"), custody).use { store ->
            store.put("hub.signingKey", "ed25519-priv-XYZ")
            assertEquals("ed25519-priv-XYZ", store.get("hub.signingKey"))
        }
    }

    @Test fun passphraseCustody_roundTrip_and_wrongPassphrase_failsClosed() {
        val dir = tempDir()
        val db = dir.resolve("s.db")
        val wrapped = dir.resolve(".cyppie/hub-master.wrapped")

        // Provision under the right passphrase; store a secret.
        SqliteSecretStore(db, PassphraseMasterKeyCustody("correct horse battery".toCharArray(), wrapped)).use {
            it.put("hub.dhKey", "x25519-priv-ABC")
        }
        assertTrue(Files.exists(wrapped))

        // Reopen with the SAME passphrase → unwraps the same keyset → the secret is readable.
        SqliteSecretStore(db, PassphraseMasterKeyCustody("correct horse battery".toCharArray(), wrapped)).use {
            assertEquals("x25519-priv-ABC", it.get("hub.dhKey"))
        }

        // A WRONG passphrase over the same wrapped keyset fails the GCM tag → custody throws → store refuses.
        assertFailsWith<SecretCipherException> {
            SqliteSecretStore(db, PassphraseMasterKeyCustody("wrong passphrase".toCharArray(), wrapped))
        }
    }

    @Test fun reservedCanaryName_cannotBeWritten() {
        SqliteSecretStore(tempDir().resolve("s.db"), keysetCustody(SecretCipherFactory.newBoxKeyset())).use { store ->
            assertFailsWith<IllegalArgumentException> { store.put("__master_key_canary__", "x") }
        }
    }

    // ---- raw-DB helpers (simulate on-disk tamper / relocation an attacker with file access could attempt) ----

    private fun corruptCiphertextRow(db: Path, name: String) {
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            val ct = c.prepareStatement("SELECT ciphertext FROM hub_secret WHERE name=?").use { ps ->
                ps.setString(1, name); ps.executeQuery().use { it.next(); it.getBytes(1) }
            }
            ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x7F).toByte()
            c.prepareStatement("UPDATE hub_secret SET ciphertext=? WHERE name=?").use { ps ->
                ps.setBytes(1, ct); ps.setString(2, name); ps.executeUpdate()
            }
        }
    }

    private fun relocateCiphertext(db: Path, from: String, to: String) {
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            val row = c.prepareStatement("SELECT ciphertext, key_ver FROM hub_secret WHERE name=?").use { ps ->
                ps.setString(1, from); ps.executeQuery().use { it.next(); it.getBytes(1) to it.getInt(2) }
            }
            c.prepareStatement("INSERT INTO hub_secret(name, ciphertext, key_ver) VALUES(?,?,?)").use { ps ->
                ps.setString(1, to); ps.setBytes(2, row.first); ps.setInt(3, row.second); ps.executeUpdate()
            }
        }
    }
}
