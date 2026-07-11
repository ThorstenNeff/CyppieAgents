package com.tneff.cyppieagents.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-434 (S-B / D2) — the default [SecretStore]: embedded SQLite at rest (owner-only `0600`, parity with the
 * other secret-at-rest stores) holding **only ciphertext** — each named secret is AEAD-sealed by the reused
 * CYP-220 [SecretCipher], whose master key comes from the injected [MasterKeyCustody]. Single held connection +
 * WAL, `synchronized` (non-`suspend` interface), matching the CYP-415 SQLite store shape.
 *
 * **Boot-time fail-closed (canary):** on open, the store verifies a reserved canary row — if it exists it MUST
 * decrypt to the known value, else the master key is wrong and the constructor **throws** (the hub does not start,
 * per the ticket's "fehlender/falscher Master-Key → Hub startet nicht"). A fresh store provisions the canary. This
 * makes a wrong key surface at boot, not lazily on the first real read. [get] additionally propagates any decrypt
 * failure (never swallowed) — the two together are the fail-closed spine.
 */
class SqliteSecretStore(
    dbPath: Path,
    custody: MasterKeyCustody,
    cipherFrom: (String) -> SecretCipher = { keyset -> SecretCipherFactory.single(1, MasterKeySource.Box(keyset)) },
) : SecretStore, AutoCloseable {

    private val lock = Any()
    private val dbPath: Path = dbPath.toAbsolutePath()
    private val conn: Connection

    // Fail-closed at construction: custody.masterKeyset() throws if the custody source is absent/unreadable, and
    // building the cipher over a malformed keyset throws too — the hub never proceeds with a broken master key.
    private val cipher: SecretCipher = cipherFrom(custody.masterKeyset())

    init {
        this.dbPath.parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:${this.dbPath}")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(CREATE_TABLE)
        }
        restrictSecretFiles() // db + WAL/SHM carry ciphertext → owner-only
        verifyOrProvisionCanary() // wrong master key → throws HERE, at boot (fail-closed)
    }

    private fun aad(name: String) = SecretAad(STORE_KEY, HUB_SCOPE, name)

    override fun put(name: String, secret: String) = synchronized(lock) {
        require(name != CANARY_NAME) { "'$CANARY_NAME' is reserved by the store" }
        writeEncrypted(name, secret)
        restrictSecretFiles()
    }

    override fun get(name: String): String? = synchronized(lock) {
        val row = readRow(name) ?: return null
        // Fail-closed: decrypt propagates SecretCipherException on wrong key / tamper / AAD mismatch — NOT caught,
        // NEVER downgraded to null-or-plaintext. Removing this propagation is the mutation the tooth catches.
        cipher.decrypt(EncryptedSecret(row.ciphertext, row.keyVersion), aad(name))
    }

    override fun contains(name: String): Boolean = synchronized(lock) {
        conn.prepareStatement("SELECT 1 FROM hub_secret WHERE name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }
    }

    override fun delete(name: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM hub_secret WHERE name=?").use { ps ->
            ps.setString(1, name); ps.executeUpdate()
        }
        Unit
    }

    override fun names(): Set<String> = synchronized(lock) {
        conn.prepareStatement("SELECT name FROM hub_secret WHERE name<>?").use { ps ->
            ps.setString(1, CANARY_NAME)
            ps.executeQuery().use { rs ->
                val out = LinkedHashSet<String>()
                while (rs.next()) out.add(rs.getString(1))
                out
            }
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    // ---- internals ----

    private class Row(val ciphertext: ByteArray, val keyVersion: Int)

    private fun readRow(name: String): Row? =
        conn.prepareStatement("SELECT ciphertext, key_ver FROM hub_secret WHERE name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) Row(rs.getBytes(1), rs.getInt(2)) else null }
        }

    private fun writeEncrypted(name: String, secret: String) {
        val enc = cipher.encrypt(secret, aad(name))
        conn.prepareStatement(
            "INSERT INTO hub_secret(name, ciphertext, key_ver) VALUES(?, ?, ?) " +
                "ON CONFLICT(name) DO UPDATE SET ciphertext=excluded.ciphertext, key_ver=excluded.key_ver",
        ).use { ps ->
            ps.setString(1, name); ps.setBytes(2, enc.ciphertext); ps.setInt(3, enc.keyVersion); ps.executeUpdate()
        }
    }

    /** Provision the canary on a fresh store, or verify it — a wrong master key fails the decrypt/compare here. */
    private fun verifyOrProvisionCanary() {
        val row = readRow(CANARY_NAME)
        if (row == null) {
            writeEncrypted(CANARY_NAME, CANARY_VALUE)
            restrictSecretFiles()
            return
        }
        val decoded = cipher.decrypt(EncryptedSecret(row.ciphertext, row.keyVersion), aad(CANARY_NAME))
        if (decoded != CANARY_VALUE) {
            throw SecretCipherException("master-key canary mismatch — refusing to start (wrong master key)")
        }
    }

    /** Restrict the DB + WAL/SHM files to `rw-------`, best-effort on POSIX (no-op elsewhere). */
    private fun restrictSecretFiles() {
        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        for (suffix in listOf("", "-wal", "-shm")) {
            val p = Path.of("$dbPath$suffix")
            if (Files.exists(p)) runCatching { Files.setPosixFilePermissions(p, owner) }
        }
    }

    private companion object {
        const val STORE_KEY = "hub_secret_store"
        const val HUB_SCOPE = "_hub" // hub-global secrets are not per-project; a fixed AAD scope
        const val CANARY_NAME = "__master_key_canary__"
        const val CANARY_VALUE = "cyppie-secret-store-canary-v1"
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS hub_secret (
              name TEXT PRIMARY KEY,
              ciphertext BLOB NOT NULL,
              key_ver INT NOT NULL
            )
        """.trimIndent()
    }
}
