package com.tneff.cyppieagents.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-286 — a short-lived, **single-use** WebSocket ticket. A browser can't set an `Authorization` header on a WS
 * upgrade, so today it passes a LONG-LIVED bearer in the `?token=` query — exposure-sensitive (URL / referrer /
 * proxy access logs; the CYP-190/292 class). A ticket removes that exposure: an already-authenticated caller
 * mints one (bound to its OWN WS-read subject) at `POST /api/ws-ticket`, then opens the socket with `?ticket=`.
 *
 * Security properties (the envelope):
 *  - **No privilege escalation** — a ticket resolves to the SAME read subject the minter already had; it only
 *    moves that identity out of a long-lived, loggable query param. The minter must already be authenticated
 *    (the mint route resolves its subject via the read-tier gate), so an unauthenticated caller mints nothing.
 *  - **Replay-resistant** — [consume] is ATOMIC single-use (`ConcurrentHashMap.remove`: exactly one caller wins,
 *    so two concurrent opens with the same ticket can never both succeed) AND the ticket expires in [ttlMs]
 *    (~30 s) — long enough to fetch-then-open, too short to replay from a log.
 *  - **Secret-at-rest** — only the SHA-256 hash is stored; the raw ticket is disclosed ONCE at [mint].
 *  - **Bounded** — single-use consume + [evictExpired] (swept on every mint) keep the store from growing on
 *    unconsumed tickets (no leak).
 *
 * The clock and RNG are injected so tests can pin expiry and entropy.
 */
class WsTicketStore(
    private val now: () -> Long,
    /** Ticket lifetime — short by design (fetch-then-open, not replay-from-log). */
    val ttlMs: Long = 30_000L,
    private val rng: SecureRandom = SecureRandom(),
) {
    private data class Rec(val subject: String, val expiresAtMs: Long)

    /** SHA-256 hash → record. NEVER the raw ticket (secret-at-rest). */
    private val byHash = ConcurrentHashMap<String, Rec>()

    /**
     * Mint a crypto-random (256-bit [SecureRandom], base64url) single-use ticket bound to [subject] — the WS-read
     * principal the caller already authenticated as. Stores only the hash; returns the raw ticket ONCE. Sweeps
     * lapsed tickets first so an unconsumed backlog can't grow the store.
     */
    fun mint(subject: String): String {
        require(subject.isNotBlank()) { "ws ticket subject must be non-blank" }
        evictExpired()
        var raw: String
        var h: String
        do { raw = secureToken(); h = sha256(raw) } while (byHash.containsKey(h))
        byHash[h] = Rec(subject, now() + ttlMs)
        return raw
    }

    /**
     * Atomically CONSUME a ticket → its subject, or null (unknown / expired / already used). The
     * `ConcurrentHashMap.remove` IS the single-use gate: the ticket is removed the instant it is looked up, so a
     * second consume (or a concurrent one) misses. Fail-closed on expiry (checked after removal — an expired
     * ticket is spent either way).
     */
    fun consume(rawTicket: String?): String? {
        val h = rawTicket?.takeIf { it.isNotBlank() }?.let { sha256(it) } ?: return null
        val rec = byHash.remove(h) ?: return null
        return if (now() < rec.expiresAtMs) rec.subject else null
    }

    private fun evictExpired() {
        val t = now()
        byHash.entries.removeIf { it.value.expiresAtMs <= t }
    }

    /** Live (un-consumed, un-evicted) ticket count — test-visibility, to prove [evictExpired] bounds growth. */
    internal fun size(): Int = byHash.size

    private fun secureToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(s: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
