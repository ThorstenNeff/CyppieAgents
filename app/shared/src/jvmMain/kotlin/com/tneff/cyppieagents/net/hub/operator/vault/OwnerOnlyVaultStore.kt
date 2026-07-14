package com.tneff.cyppieagents.net.hub.operator.vault

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * CYP-542 / B1 — the jvm [VaultStore] actual: the sealed vault blob at [file], written **atomically owner-only**
 * (the CYP-525 `writeOwnerOnly` pattern, F1). The blob is AEAD ciphertext so the file never holds plaintext key
 * material, but owner-only (`0600`-from-birth via a POSIX temp + atomic move, `0700` parent) keeps even the ciphertext
 * + salt off other users. A present-but-unreadable file returns `null` from [read] ⇒ the vault surfaces CORRUPT (③),
 * never a silent re-enroll.
 */
class OwnerOnlyVaultStore(private val file: Path) : VaultStore {

    override fun exists(): Boolean = Files.exists(file)

    override fun read(): ByteArray? = runCatching { Files.readAllBytes(file) }.getOrNull()

    override fun write(bytes: ByteArray) {
        val dir = file.parent
        if (dir != null && !Files.exists(dir)) {
            runCatching { Files.createDirectories(dir) }
            runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) } // 0700
        }
        // Owner-only FROM BIRTH (POSIX attribute at creation, not a later chmod) in the SAME dir, then atomic move.
        val ownerOnly = runCatching {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) // 0600
        }.getOrNull()
        val tmp = if (ownerOnly != null) {
            Files.createTempFile(dir, ".vault", ".tmp", ownerOnly)
        } else {
            Files.createTempFile(dir, ".vault", ".tmp") // non-POSIX FS: best-effort (below)
        }
        try {
            Files.write(tmp, bytes)
            if (ownerOnly == null) runCatching { tmp.toFile().setReadable(false, false); tmp.toFile().setReadable(true, true); tmp.toFile().setWritable(false, false); tmp.toFile().setWritable(true, true) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    override fun delete() { runCatching { Files.deleteIfExists(file) } }
}
