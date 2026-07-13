package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.AtomicFileWrite
import com.tneff.cyppieagents.crypto.EncryptedSecret
import com.tneff.cyppieagents.crypto.SecretAad
import com.tneff.cyppieagents.crypto.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/** CYP-525 — the FINALIZED combined enrollment: the device anchor AND the backup-code salted hashes as ONE unit. */
data class FinalizedEnrollment(val device: EnrolledOperatorDevice, val codes: List<BackupCodeEntry>)

/**
 * CYP-525 (GE5/GE7 — H1/H1b) — the durable, crash-atomic, tamper-evident store for the FINALIZED combined enrollment
 * record {device anchor + code-hashes}. The anchor and the hashes commit **TOGETHER as ONE encrypted value** written via
 * [AtomicFileWrite] (temp → fsync → atomic rename → dir fsync), so a power-loss crash can never leave a 2-key split nor
 * a torn record (H1/H1b). [read] decrypts (Tink AEAD, tamper-evident + relocation-proof AAD):
 *  - **absent → `null`** (a legit pre-finalize crash left nothing → the gate re-mints FRESH — safe);
 *  - **present-but-tampered → THROWS** ([com.tneff.cyppieagents.crypto.SecretCipherException], fail-closed) — a
 *    byte-flip of the at-rest anchor is refused, never treated as "empty → re-enroll" (that would be a tamper→re-enroll
 *    seizure vector). The two cases are DISJOINT (atomic-rename removes the third, torn, state).
 */
class FinalizedEnrollmentStore(
    private val cipher: SecretCipher,
    private val file: File,
    private val projectId: String,
) {
    fun read(): FinalizedEnrollment? {
        if (!file.exists()) return null
        val raw = file.readBytes()
        require(raw.size >= 4) { "finalized enrollment record is truncated" }
        val keyVersion = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        // Tamper / wrong-key / AAD-mismatch → SecretCipherException (fail-closed); a corrupt JSON likewise throws.
        val json = cipher.decrypt(EncryptedSecret(raw.copyOfRange(4, raw.size), keyVersion), aad())
        val p = JSON.decodeFromString(Persisted.serializer(), json)
        return FinalizedEnrollment(
            device = EnrolledOperatorDevice(
                deviceId = p.device.deviceId,
                alg = DeviceKeyAlg.valueOf(p.device.alg),
                publicKey = B64.decode(p.device.publicKeyB64),
                credentialId = p.device.credentialIdB64?.let { B64.decode(it) },
            ),
            codes = p.codes.map { BackupCodeEntry(B64.decode(it.saltB64), B64.decode(it.hashB64), it.consumed) },
        )
    }

    /** Commit the combined record crash-atomically. [crashHook] is a test seam (a crash after fsync, before rename). */
    fun commit(record: FinalizedEnrollment, crashHook: () -> Unit = {}) {
        val p = Persisted(
            device = PDevice(
                deviceId = record.device.deviceId,
                alg = record.device.alg.name,
                publicKeyB64 = B64E.encodeToString(record.device.publicKey),
                credentialIdB64 = record.device.credentialId?.let { B64E.encodeToString(it) },
            ),
            codes = record.codes.map { PCode(B64E.encodeToString(it.salt), B64E.encodeToString(it.hash), it.consumed) },
        )
        val enc = cipher.encrypt(JSON.encodeToString(Persisted.serializer(), p), aad())
        val framed = beInt(enc.keyVersion) + enc.ciphertext
        AtomicFileWrite.write(file, framed, crashHook)
    }

    private fun aad() = SecretAad("operator.finalized-enrollment", projectId, "record")
    private fun beInt(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    @Serializable private data class Persisted(val device: PDevice, val codes: List<PCode>)
    @Serializable private data class PDevice(val deviceId: String, val alg: String, val publicKeyB64: String, val credentialIdB64: String?)
    @Serializable private data class PCode(val saltB64: String, val hashB64: String, val consumed: Boolean)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val B64: Base64.Decoder = Base64.getDecoder()
        val B64E: Base64.Encoder = Base64.getEncoder()
    }
}
