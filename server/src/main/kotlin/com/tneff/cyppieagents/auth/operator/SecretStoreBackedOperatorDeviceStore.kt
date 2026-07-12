package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.SecretCipherException
import com.tneff.cyppieagents.crypto.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * CYP-472 — a **durable** [OperatorDeviceStore] backed by the S-B [SecretStore] (CYP-434), so a First-Device-Enroll
 * survives a hub restart. The enrolled device is the **PoP anchor**: its integrity is security-critical (a swapped
 * anchor = operator seizure), so it is held **encrypted-at-rest + tamper-evident** in the SecretStore — the exact
 * pattern S-C uses for the hub private keys — rather than in a plaintext file.
 *
 * **Fail-closed:** a wrong master key / tampered blob makes the SecretStore throw ([SecretCipherException]) — never a
 * silent "not enrolled" that would invite a fresh enroll over a corrupt/attacked anchor. A malformed stored value is
 * likewise a corruption (throws), not a "no device" — the anchor is never silently dropped. Recovery/re-enroll stays
 * the Q6-gated seam ([OperatorDeviceRecovery]); this only makes First-Enroll durable.
 *
 * Wiring is **opt-in** (parity with the `CYPPIE_MASTER_KEY`-gated S-C custody): the store is constructed only when a
 * SecretStore is available, so today's local server is unchanged.
 */
class SecretStoreBackedOperatorDeviceStore(
    private val secrets: SecretStore,
    private val name: String = "operator.device",
) : OperatorDeviceStore {

    override fun enrolled(): EnrolledOperatorDevice? {
        val json = secrets.get(name) ?: return null // absent = genuinely not enrolled (get THROWS on wrong-key/tamper)
        val p = runCatching { JSON.decodeFromString(Persisted.serializer(), json) }.getOrElse {
            // A corrupt/undecodable anchor is NOT "not enrolled" — fail closed rather than allow a silent re-enroll.
            throw SecretCipherException("operator device anchor is corrupt (refusing to treat as un-enrolled)", it)
        }
        return EnrolledOperatorDevice(
            deviceId = p.deviceId,
            alg = DeviceKeyAlg.valueOf(p.alg),
            publicKey = B64.decode(p.publicKeyB64),
            credentialId = p.credentialIdB64?.let { B64.decode(it) },
        )
    }

    override fun save(device: EnrolledOperatorDevice) {
        val p = Persisted(
            deviceId = device.deviceId,
            alg = device.alg.name,
            publicKeyB64 = B64E.encodeToString(device.publicKey),
            credentialIdB64 = device.credentialId?.let { B64E.encodeToString(it) },
        )
        secrets.put(name, JSON.encodeToString(Persisted.serializer(), p))
    }

    /** The at-rest form (bytes → base64 so it round-trips through the String-valued SecretStore). */
    @Serializable
    private data class Persisted(
        val deviceId: String,
        val alg: String,
        val publicKeyB64: String,
        val credentialIdB64: String?,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val B64: Base64.Decoder = Base64.getDecoder()
        val B64E: Base64.Encoder = Base64.getEncoder()
    }
}
