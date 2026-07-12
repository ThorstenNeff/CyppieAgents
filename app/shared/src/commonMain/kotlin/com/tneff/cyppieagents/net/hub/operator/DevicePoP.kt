package com.tneff.cyppieagents.net.hub.operator

/**
 * CYP-443 Slice 2 (Epic CYP-427 Phase-2) — the operator **Proof-of-Possession** (RR2-B). A discriminated union:
 * two branches, **OS-selected, server-authoritative (no client downgrade)**, both channel-bound to the LIVE Noise
 * `h` via [operatorAuthChallenge]. The CP forges the identity assertion but **never** this — the operator holds the
 * key (anti-CP-seizure holds fully). Transport-independent: this builds the PoP; sending it over the tunnel + the
 * hub-grant round-trip is the transport-dependent wiring that waits on the RR5 decision.
 */
sealed interface DevicePoP {
    /**
     * Cross-platform base (incl. Linux): a raw EdDSA signature by the **software device-key** over the challenge.
     * Anti-CP-seizure FULL; anti-local-malware weaker than hardware-UV (the app-PIN is keyloggable) — the conscious
     * no-hardware trade-off (Auftraggeber). Verifier = plain Ed25519 verify over the challenge.
     */
    data class Raw(val signature: ByteArray) : DevicePoP

    /**
     * Progressive enhancement where the OS has a platform authenticator (macOS Touch-ID / Windows Hello via
     * libfido2/FFM). CTAP2 assertion; verifier = raw-CTAP (sig over `authenticatorData ‖ SHA-256(challenge)`, UV
     * flag set, signCount lenient). Not runtime-reachable on Linux (no platform authenticator).
     */
    data class Fido2(
        val credentialId: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    ) : DevicePoP
}

/** The PoP purpose tag (doc 16 §7) — domain-separates this signature from any other use of the device-key. */
const val OPERATOR_AUTH_PURPOSE = "operator-auth"

/**
 * The channel-bound challenge the device-key attests: each field **4-byte-BE length-prefixed** then concatenated
 * (`len‖h · len‖hubId · len‖nonce · len‖purpose`) so no field boundary is ambiguous (a bare `a‖b` concat could
 * collide). Bound to the **live tunnel `h`** (CI-2): a PoP built for one session's `h` cannot be replayed onto
 * another — the hub recomputes this from its own `h`/`hubId`/purpose + the carried nonce and rejects a mismatch.
 * The Raw branch EdDSA-signs these bytes directly; the Fido2 branch uses `SHA-256(these)` as the CTAP clientDataHash.
 */
fun operatorAuthChallenge(handshakeHash: ByteArray, hubId: String, nonce: ByteArray): ByteArray {
    val fields = listOf(
        handshakeHash,
        hubId.encodeToByteArray(),
        nonce,
        OPERATOR_AUTH_PURPOSE.encodeToByteArray(),
    )
    val out = ByteArray(fields.sumOf { 4 + it.size })
    var i = 0
    for (f in fields) {
        out[i++] = (f.size ushr 24).toByte()
        out[i++] = (f.size ushr 16).toByte()
        out[i++] = (f.size ushr 8).toByte()
        out[i++] = f.size.toByte()
        f.copyInto(out, i)
        i += f.size
    }
    return out
}
