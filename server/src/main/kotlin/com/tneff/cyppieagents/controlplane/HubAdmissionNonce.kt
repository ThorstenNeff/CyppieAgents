package com.tneff.cyppieagents.controlplane

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-512 (Epic CYP-427 Phase-2, activation) — the CP-side **admission challenge nonce** store. The CP issues a fresh
 * random nonce (the PoP challenge, R3), the hub signs the whole registration TRANSCRIPT over it (CYP-451), and the CP
 * admits ONLY if the echoed nonce is one it issued AND still **unused** — then it **consumes** it. Single-use +
 * anti-replay: a replayed registration (same nonce) is rejected because the nonce is already consumed.
 *
 * In-memory (the CP process holds the outstanding challenges); a nonce is short-lived (a hub fetches → registers at
 * once). [SecureRandom] 32-byte nonces — unguessable, no birthday concern.
 */
class HubAdmissionNonce(
    private val nonceBytes: Int = 32,
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    /** Outstanding issued-and-unused nonces (base64 key → presence). */
    private val issued = ConcurrentHashMap.newKeySet<String>()

    private fun key(nonce: ByteArray) = Base64.getEncoder().encodeToString(nonce)

    /** Issue a fresh single-use challenge nonce and remember it as outstanding. */
    fun issue(): ByteArray {
        val nonce = random(nonceBytes)
        issued.add(key(nonce))
        return nonce
    }

    /** Consume [nonce]: `true` iff it was issued AND still unused (then it is removed — single-use). A blank / never-
     *  issued / already-consumed nonce → `false` (anti-replay, fail-closed). */
    fun consume(nonce: ByteArray): Boolean {
        if (nonce.isEmpty()) return false
        return issued.remove(key(nonce))
    }

    fun outstanding(): Int = issued.size
}
