package com.tneff.cyppieagents.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-234b-1 — the **participant-scoped token class** (the ratified BYO-machine credential, spec §2.2): a
 * token issued to an EXTERNAL, non-browser frontend consumer (Go/Godot/CLI) that resolves to a first-class
 * ACL read-**subject** — distinct from a per-spawned-agent token ([com.tneff.cyppieagents.routing.TokenRegistry],
 * which resolves to an agentId). Its tier is [ParticipantTier.READ] by construction: it NEVER carries
 * operator/member authorization — the [com.tneff.cyppieagents.model.AclMatrix] `canRead`/`canWrite` check is the
 * ONLY authz, exactly like a human MEMBER's identityId (fail-closed empty until an operator grants per-channel
 * read). 234b's load-bearing invariant: this token only widens WHO reaches the ACL chokepoints, never the authz.
 *
 * **Secret-at-rest:** the raw token is NEVER stored — the map is keyed by the token's SHA-256 [hash]. A leak of
 * this store (or a persisted snapshot) discloses no usable credential. The raw value is disclosed exactly ONCE,
 * at [mint]. It is never logged (callers must not log it either).
 *
 * Lifecycle (#8): [mint] (server-generated, optional expiry), [revoke] / [revokeSubject] (immediate deny),
 * [resolve] fail-closed on unknown / expired. Persistence layers on via [bindHash] (hashes only), like
 * `TokenRegistry.bind`; this class is the in-memory core + hygiene.
 */
enum class ParticipantTier { READ }

/**
 * CYP-297 (Layer 1, load-bearing) — the **reserved ACL principal** a participant token resolves to. A participant
 * token's operator-chosen `subject` is a free-form String living in the SAME namespace as agentIds, the
 * [com.tneff.cyppieagents.comm.HubState.OPERATOR_ID] (`"operator"`), and Kratos identityIds. Resolving it VERBATIM
 * (the pre-CYP-297 bug) let a token minted `subject="operator"`/`subject="<agentId>"` inherit that principal's
 * canRead/canWrite rows → operator-write / agent-impersonation. Carrying it under the reserved [PREFIX] makes the
 * resolved principal **structurally unable** to equal any bare id, so `canRead`/`canWrite` for it is always a
 * DISTINCT, fail-closed-empty row (inherits no foreign grant). Single-sourced here; every read-resolver applies it
 * at the one chokepoint ([com.tneff.cyppieagents.routing.participantSubject]). The bare [subject] stays the
 * operator-facing label in mint/summaries — only the AUTHZ principal is namespaced.
 */
object ParticipantPrincipal {
    const val PREFIX = "participant:"

    /** The reserved principal for a participant token's raw [subject] — never equal to an agentId/OPERATOR_ID/identityId. */
    fun of(subject: String): String = "$PREFIX$subject"

    /** True if [principal] is a participant-scoped principal (carries the reserved namespace). */
    fun isParticipant(principal: String): Boolean = principal.startsWith(PREFIX)
}

data class ParticipantTokenRecord(
    val subject: String,
    val tier: ParticipantTier,
    val issuedAtMs: Long,
    /** epoch-ms expiry, or null for no expiry. */
    val expiresAtMs: Long?,
)

class ParticipantTokenStore(private val now: () -> Long) {

    // token SHA-256 HASH → record. NEVER the raw token (secret-at-rest).
    private val byHash = ConcurrentHashMap<String, ParticipantTokenRecord>()

    /**
     * Mint a cryptographically-random participant token (256-bit SecureRandom, base64url), store only its
     * hash + the record, and return the raw value (disclosed ONCE). [ttlMs] null → no expiry. Regenerates on
     * the astronomically-unlikely hash collision so the credential is always unique.
     */
    fun mint(subject: String, ttlMs: Long? = null, tier: ParticipantTier = ParticipantTier.READ): String {
        require(subject.isNotBlank()) { "participant token subject must be non-blank" }
        require(ttlMs == null || ttlMs > 0) { "ttlMs must be positive when set" }
        var raw: String
        var h: String
        do { raw = secureToken(); h = hash(raw) } while (byHash.containsKey(h))
        byHash[h] = ParticipantTokenRecord(subject, tier, now(), ttlMs?.let { now() + it })
        return raw
    }

    /** Resolve a raw token to its LIVE record, or null (unknown / expired / revoked). Fail-closed. */
    fun resolve(rawToken: String?): ParticipantTokenRecord? {
        val rec = rawToken?.takeIf { it.isNotBlank() }?.let { byHash[hash(it)] } ?: return null
        if (rec.expiresAtMs != null && now() >= rec.expiresAtMs) return null // expired → fail-closed
        return rec
    }

    /** The ACL read-subject for a participant token (a first-class read-subject, fail-closed until granted). */
    fun subjectFor(rawToken: String?): String? = resolve(rawToken)?.subject

    /** #8 — revoke a specific token (idempotent). After this, [resolve] is immediately null. */
    fun revoke(rawToken: String): Boolean = byHash.remove(hash(rawToken)) != null

    /** #8 — revoke every token of [subject] (idempotent); returns the count removed. */
    fun revokeSubject(subject: String): Int {
        val n = byHash.entries.count { it.value.subject == subject }
        byHash.entries.removeIf { it.value.subject == subject }
        return n
    }

    /** Boot restore of a persisted `(hash → record)` binding — the store persists HASHES, never raw tokens. */
    fun bindHash(tokenHash: String, record: ParticipantTokenRecord) { byHash[tokenHash] = record }

    fun count(): Int = byHash.size

    /**
     * CYP-234b-3 — secret-free summaries for the admin list endpoint: the records (subject / tier / issuedAt /
     * expiry) WITHOUT the map keys (hashes) or any raw token. A record carries no credential, so this discloses
     * nothing usable — the operator sees WHO has a token + when it expires, never the token itself.
     */
    fun summaries(): List<ParticipantTokenRecord> = byHash.values.toList()

    /** Test-only: the stored KEYS (hashes). Lets a tooth prove the raw token is never a key (hashed-at-rest). */
    internal fun storedKeys(): Set<String> = byHash.keys.toSet()

    private fun secureToken(): String {
        val b = ByteArray(32) // 256-bit
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
