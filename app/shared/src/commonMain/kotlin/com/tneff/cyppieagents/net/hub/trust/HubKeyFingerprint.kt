package com.tneff.cyppieagents.net.hub.trust

/**
 * CYP-478 — the **human-comparable fingerprint** of a hub's Noise static (DH) public key, for the
 * out-of-band (OOB) confirmation that guards TOFU (SSH-known_hosts style). Format = the SHA-256 digest of the
 * raw key bytes rendered as **colon-separated lowercase hex** (`ab:cd:…`, matching the `expectedFingerprint`
 * convention the seam already uses). Hashing (rather than showing the raw key) gives a fixed 32-group string
 * regardless of key type and is the recognizable fingerprint an operator reads aloud / compares against what
 * the hub admin published OOB.
 *
 * Deterministic + pure → identical on every target; the poisoned-first-pin defence rests on the operator
 * comparing THIS string to a trusted OOB source, so it must never depend on platform crypto availability.
 */
object HubKeyFingerprint {

    private const val HEX = "0123456789abcdef"

    /** SHA-256(key) as colon-separated lowercase hex. */
    fun of(hubStatic: ByteArray): String = colonHex(Sha256.digest(hubStatic))

    private fun colonHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(':')
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}
