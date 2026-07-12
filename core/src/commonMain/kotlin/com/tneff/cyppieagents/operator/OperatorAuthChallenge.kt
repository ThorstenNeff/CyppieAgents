package com.tneff.cyppieagents.operator

/**
 * CYP-473 H2 — the **single source** of the operator-auth channel-bound challenge, in `:core` so the CLIENT
 * (`app/shared`, builds the PoP) and the SERVER (`:server`, verifies it) share ONE definition. Previously
 * hand-mirrored in both strands (byte-identical only by hand + only the server had a golden-hex tooth): a future
 * client-side field-reorder/delimiter change would leave both sides green yet silently break server verification of
 * a real client PoP. Consolidating here eliminates that drift class entirely.
 */

/** The PoP purpose tag (doc 16 §7) — domain-separates this signature from any other use of the device-key. */
const val OPERATOR_AUTH_PURPOSE = "operator-auth"

/**
 * The channel-bound challenge the operator device-key attests: each field **4-byte-BE length-prefixed** then
 * concatenated (`len‖h · len‖hubId · len‖nonce · len‖purpose`), injective so no field boundary is ambiguous (a bare
 * `a‖b` concat could collide two distinct triples onto identical bytes). Bound to the **live Noise `h`** (CI-2): a
 * PoP built for one session's `h` cannot be replayed onto another — the hub recomputes this from its own
 * `h`/`hubId`/purpose + the carried nonce and rejects a mismatch. The Raw branch EdDSA-signs these bytes directly;
 * the Fido2 branch uses `SHA-256(these)` as the CTAP clientDataHash.
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
