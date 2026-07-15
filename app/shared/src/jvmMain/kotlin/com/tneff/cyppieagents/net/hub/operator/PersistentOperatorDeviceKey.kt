package com.tneff.cyppieagents.net.hub.operator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.slf4j.LoggerFactory

/**
 * CYP-525 Inc 2 — **durable** Ed25519 operator device-key custody (the CYP-413 `DEVICE_SECURE` intent, jvm actual).
 *
 * The bug it fixes: [KeystoreOperatorDeviceKeyStore.generateDeviceKey] minted a **fresh key every launch** → the
 * device PoP never matched the hub's enrolled anchor (`bad_signature`) and CONNECTED was unreachable. [loadOrGenerate]
 * persists ONE key and reuses it across launches: first run generates + persists, later runs load it back.
 *
 * Custody is the **interim app-dir** form (design §3): the private key is written PKCS#8 at [keyFile] with owner-only
 * `0600` perms under a `0700` parent — app-scope-readable, so the OS-keystore binding is the later hardening; the key
 * is **never logged** and **never the tracked tree** (it lives under the user home, e.g. `~/.cyppie/`). WebAuthn (b)
 * sidesteps the at-rest question entirely (non-exportable in the authenticator) and stays the ratified end-state.
 *
 * File format: `[4-byte big-endian len(PKCS#8 private)] ‖ PKCS#8 private ‖ X.509 public` — both encodings are stored
 * because the JDK Ed25519 provider cannot re-derive the public key from the private one alone. A present-but-unreadable
 * file is treated as "no key" and regenerated (a corrupt custody file must not brick connect; the hub then sees a new
 * key → the enroll/recovery flow, CYP-525 Inc 3).
 */
class PersistentOperatorDeviceKey(private val keyFile: Path) {

    private val log = LoggerFactory.getLogger("operator.deviceKey")

    /** Load the persisted Ed25519 device key, or generate + persist one on first run (idempotent across launches). */
    fun loadOrGenerate(): KeyPair {
        if (Files.exists(keyFile)) {
            runCatching { load() }.getOrNull()?.let { return it }
            // F3 (silent-swallow fix — WARN-LOG ONLY; the fail-closed-vs-regenerate POSTURE is a PO1 security
            // decision and is deliberately NOT changed here). A PRESENT device-key file that failed to load is about
            // to be regenerated, which SILENTLY changes this device's identity → the hub then sees a new key and
            // forces re-enrollment. Was a bare `.getOrNull() ?: generateAndPersist()` (the CYP-575 class): a corrupt
            // custody file caused an unexplained re-enroll with no trace. Log it so the cause is diagnosable.
            log.warn(
                "operator device-key file present but UNREADABLE at {} — regenerating a fresh device identity; the hub will see a new key and require re-enrollment (corrupt custody file)",
                keyFile,
            )
        }
        return generateAndPersist()
    }

    /** CYP-542 migration — the persisted key iff the file exists AND is readable, else `null`. **Never generates**
     *  (unlike [loadOrGenerate]) so B1 can migrate an existing plaintext key without accidentally minting a new one. */
    fun loadOrNull(): KeyPair? =
        if (Files.exists(keyFile)) runCatching { load() }.getOrNull() else null

    /** Whether a plaintext key file is present (regardless of readability). */
    fun exists(): Boolean = Files.exists(keyFile)

    /** Delete the plaintext key file (CYP-542 migration, AFTER the vault re-seal verifies). No-op if absent. */
    fun delete() { runCatching { Files.deleteIfExists(keyFile) } }

    private fun load(): KeyPair {
        val bytes = Files.readAllBytes(keyFile)
        val privLen = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val priv = bytes.copyOfRange(4, 4 + privLen)
        val pub = bytes.copyOfRange(4 + privLen, bytes.size)
        val kf = KeyFactory.getInstance("Ed25519")
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(pub)),
            kf.generatePrivate(PKCS8EncodedKeySpec(priv)),
        )
    }

    private fun generateAndPersist(): KeyPair {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val priv = kp.private.encoded // PKCS#8
        val pub = kp.public.encoded   // X.509 SPKI
        val out = ByteArray(4 + priv.size + pub.size)
        out[0] = (priv.size ushr 24).toByte(); out[1] = (priv.size ushr 16).toByte()
        out[2] = (priv.size ushr 8).toByte(); out[3] = priv.size.toByte()
        priv.copyInto(out, 4)
        pub.copyInto(out, 4 + priv.size)
        writeOwnerOnly(out)
        return kp
    }

    /**
     * F1 (Reviewer, security): write [bytes] to [keyFile] **atomically owner-only** — the PKCS#8 private key must
     * NEVER touch disk world-readable (0644 via umask), not even in the window between a naive `Files.write` and a
     * follow-up chmod. So: create an owner-only (`0600`-from-birth) temp in the SAME dir (POSIX file-attribute at
     * creation, not a later chmod), write into it, then **atomically move** it over the target (this also covers the
     * regenerate-over-corrupt overwrite). On a non-POSIX FS the attribute is unsupported → best-effort chmod fallback.
     */
    private fun writeOwnerOnly(bytes: ByteArray) {
        val parent = requireNotNull(keyFile.parent) { "device key path must have a parent dir" }
        val ownerFile = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val ownerDir = ownerFile + PosixFilePermission.OWNER_EXECUTE
        if (!Files.exists(parent)) {
            runCatching { Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(ownerDir)) }
                .onFailure { Files.createDirectories(parent) } // non-POSIX fallback
        }
        runCatching { Files.setPosixFilePermissions(parent, ownerDir) } // tighten an existing parent (best-effort)
        // Create the temp owner-only AT CREATION (never 0644), same dir so ATOMIC_MOVE stays on one filesystem.
        val tmp = runCatching {
            Files.createTempFile(parent, ".op-device", ".tmp", PosixFilePermissions.asFileAttribute(ownerFile))
        }.getOrElse {
            Files.createTempFile(parent, ".op-device", ".tmp").also { runCatching { Files.setPosixFilePermissions(it, ownerFile) } }
        }
        try {
            Files.write(tmp, bytes)
            runCatching { Files.setPosixFilePermissions(tmp, ownerFile) } // belt-and-suspenders
            runCatching { Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING) } // non-atomic FS fallback
        } finally {
            runCatching { Files.deleteIfExists(tmp) } // never leak the key material via an orphaned temp
        }
    }

    companion object {
        /** Default custody path under the user home: `~/.cyppie/operator-device.key` (never the repo tree). */
        fun defaultKeyFile(): Path =
            Paths.get(System.getProperty("user.home") ?: ".", ".cyppie", "operator-device.key")
    }
}
