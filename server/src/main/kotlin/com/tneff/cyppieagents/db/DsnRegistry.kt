package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.crypto.EncryptedSecret
import com.tneff.cyppieagents.crypto.SecretAad
import com.tneff.cyppieagents.crypto.SecretCipher
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

/** Where a DSN came from (Design §1.1): our managed Aiven default vs a user-brought instance. */
enum class DsnTierOrigin { AIVEN_MANAGED, BYO }

/**
 * A named Postgres connection descriptor (Design §1.1) — the **non-secret** view (the password is held
 * encrypted, never on this DTO / the wire / a log). Keyed by [dsnId].
 */
@Serializable
data class DsnDescriptor(
    val dsnId: String,
    val label: String,
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val sslMode: String = "require",
    val tierOrigin: DsnTierOrigin = DsnTierOrigin.BYO,
    val createdBy: String,
    val createdAt: Long,
) {
    /** The JDBC URL (no credentials — user/password go on the pool config, never in the URL/logs). */
    fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/$database?sslmode=$sslMode"
}

/** The persisted form: the descriptor + the **encrypted** password (base64 ciphertext + key version). */
@Serializable
private data class StoredDsn(val descriptor: DsnDescriptor, val passwordCtB64: String, val passwordKeyVersion: Int)

/** A resolved DSN with the DECRYPTED password — internal only; the password is NEVER serialized or logged. */
class ResolvedDsn(val descriptor: DsnDescriptor, val password: String)

/**
 * CYP-220 Phase 2b — the registry of named DSNs (Design §1.1). A **bootstrap store that MUST stay on our infra**
 * (§7.3): it holds the pointers to every user DB, so it cannot live inside a user DB. The password is encrypted
 * at rest with the Phase-2a [SecretCipher] (AAD-bound to `dsn_registry|{dsnId}|password`, so a ciphertext can't
 * be relocated), so **the master key — not the user — protects it**. `null` file → in-memory (tests / dry boots).
 */
@com.tneff.cyppieagents.tier.StoreKey("dsn_registry")
interface DsnRegistry {
    /** Add or replace a DSN; [password] is encrypted at rest. Returns the (non-secret) descriptor. */
    fun put(descriptor: DsnDescriptor, password: String): DsnDescriptor
    fun descriptor(dsnId: String): DsnDescriptor?
    fun list(): List<DsnDescriptor>
    /** Resolve to a connection detail with the DECRYPTED password (for the pool config). null if unknown. */
    fun resolve(dsnId: String): ResolvedDsn?
    fun remove(dsnId: String): Boolean

    companion object {
        operator fun invoke(file: File?, cipher: SecretCipher): DsnRegistry = FileDsnRegistry(file, cipher)
    }
}

class FileDsnRegistry(private val file: File?, private val cipher: SecretCipher) : DsnRegistry {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("db.dsnregistry")
    private val byId: MutableMap<String, StoredDsn> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists() && f.length() > 0) {
            try {
                byId.putAll(CommJson.decodeFromString<Map<String, StoredDsn>>(f.readText()))
            } catch (e: Exception) {
                // Holds (encrypted) secrets → do NOT back up or log contents. Start empty rather than brick boot.
                log.error("corrupt DSN registry at {}; starting empty (contents not logged)", f)
            }
        }
    }

    private fun aad(dsnId: String) = SecretAad(storeKey = "dsn_registry", projectId = dsnId, field = "password")

    override fun put(descriptor: DsnDescriptor, password: String): DsnDescriptor = synchronized(lock) {
        val enc: EncryptedSecret = cipher.encrypt(password, aad(descriptor.dsnId))
        byId[descriptor.dsnId] = StoredDsn(descriptor, Base64.getEncoder().encodeToString(enc.ciphertext), enc.keyVersion)
        persist()
        descriptor
    }

    override fun descriptor(dsnId: String): DsnDescriptor? = synchronized(lock) { byId[dsnId]?.descriptor }

    override fun list(): List<DsnDescriptor> = synchronized(lock) { byId.values.map { it.descriptor } }

    override fun resolve(dsnId: String): ResolvedDsn? = synchronized(lock) {
        val s = byId[dsnId] ?: return null
        val plaintext = cipher.decrypt(EncryptedSecret(Base64.getDecoder().decode(s.passwordCtB64), s.passwordKeyVersion), aad(dsnId))
        ResolvedDsn(s.descriptor, plaintext)
    }

    override fun remove(dsnId: String): Boolean = synchronized(lock) {
        val removed = byId.remove(dsnId) != null
        if (removed) persist()
        removed
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { it.mkdirs(); restrictDirToOwner(it) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.delete(); tmp.createNewFile(); restrictToOwner(tmp) // 0600-FIRST
        tmp.writeText(CommJson.encodeToString(byId.toMap()))
        restrictToOwner(tmp)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(f)
    }

    private fun restrictToOwner(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }.onFailure {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    private fun restrictDirToOwner(d: File) {
        runCatching {
            Files.setPosixFilePermissions(
                d.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }.onFailure {
            d.setReadable(false, false); d.setReadable(true, true)
            d.setWritable(false, false); d.setWritable(true, true)
            d.setExecutable(false, false); d.setExecutable(true, true)
        }
    }
}
