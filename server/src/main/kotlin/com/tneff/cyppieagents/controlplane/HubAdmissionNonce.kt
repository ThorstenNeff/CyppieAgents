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
 *
 * CYP-563 — the store is BOUNDED: an issued-but-never-consumed nonce is evicted after [ttlMs] (nonces are short-lived
 * by design), and a hard [maxOutstanding] cap backstops eviction. Previously the set only ever grew (issue adds,
 * consume removes) with no TTL/cap → an operator looping `GET /cp/challenge` without `/cp/admit` leaked memory
 * unboundedly (operator-only, but no reason to be unbounded). An EXPIRED nonce is also rejected on consume
 * (fail-closed), so lengthening a captured challenge past its window doesn't help.
 */
class HubAdmissionNonce(
    private val nonceBytes: Int = 32,
    /** CYP-563 — a challenge older than this is evicted / rejected on consume (nonces are fetch→register-at-once). */
    private val ttlMs: Long = 5 * 60_000L,
    /** CYP-563 — a hard backstop cap on outstanding challenges (defense in depth beyond TTL eviction). */
    private val maxOutstanding: Int = 4096,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    /** Outstanding issued-and-unused nonces (base64 key → issued-at epoch ms, for TTL eviction). */
    private val issued = ConcurrentHashMap<String, Long>()

    private fun key(nonce: ByteArray) = Base64.getEncoder().encodeToString(nonce)

    /** Issue a fresh single-use challenge nonce and remember it as outstanding (bounded: expired entries are evicted
     *  first, and the hard cap drops the oldest outstanding should eviction not free room). */
    fun issue(): ByteArray {
        evictExpired()
        if (issued.size >= maxOutstanding) {
            // backstop: drop the oldest outstanding so memory stays bounded even under a burst within the TTL window.
            issued.entries.minByOrNull { it.value }?.let { issued.remove(it.key, it.value) }
        }
        val nonce = random(nonceBytes)
        issued[key(nonce)] = now()
        return nonce
    }

    /** Consume [nonce]: `true` iff it was issued, still unused, AND not expired (then it is removed — single-use). A
     *  blank / never-issued / already-consumed / EXPIRED nonce → `false` (anti-replay + fail-closed). */
    fun consume(nonce: ByteArray): Boolean {
        if (nonce.isEmpty()) return false
        val issuedAt = issued.remove(key(nonce)) ?: return false
        return now() - issuedAt <= ttlMs
    }

    fun outstanding(): Int {
        evictExpired()
        return issued.size
    }

    private fun evictExpired() {
        val cutoff = now() - ttlMs
        issued.entries.removeIf { it.value < cutoff }
    }
}
