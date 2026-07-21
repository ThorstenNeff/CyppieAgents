package com.tneff.cyppieagents.tier

import com.tneff.cyppieagents.routing.ConflictException
import org.slf4j.LoggerFactory

/**
 * CYP-220 Phase 5 — the ratified Free-tier quota (Design §7.2): a per-ACCOUNT storage + row cap on the managed
 * fallback slice. Only the offloadable operational stores count against it; the bootstrap/auth stores are exempt
 * (they are tiny + necessary — [com.tneff.cyppieagents.tier.StoreResidency.MUST_STAY_HOME]).
 */
data class FreeQuotaLimits(
    val maxBytes: Long = 50L * 1024 * 1024, // ~50 MB / account (ratified)
    val freeKeepLastRows: Long = 200,       // tight Free event/transcript retention (ratified)
    val softFraction: Double = 0.8,         // SOFT (banner/CTA) at 80% of the cap
)

/** A per-account usage roll-up (Design §7.2). */
data class QuotaUsage(val bytesUsed: Long, val rowsUsed: Long)

/** Graduated enforcement level (Design §7.2). */
enum class QuotaLevel { OK, SOFT, HARD }

/**
 * Evaluates usage against the quota — a PURE function. OK → SOFT (banner/CTA) → HARD (block a NEW write).
 * **NEVER delete-to-fit:** this only decides whether to admit the next write; it never removes existing data
 * (retention trims events separately). At/over the cap = HARD; at/over softFraction = SOFT.
 */
class QuotaEnforcer(private val limits: FreeQuotaLimits = FreeQuotaLimits()) {
    fun evaluate(usage: QuotaUsage): QuotaLevel = when {
        usage.bytesUsed >= limits.maxBytes -> QuotaLevel.HARD
        usage.bytesUsed >= (limits.maxBytes * limits.softFraction).toLong() -> QuotaLevel.SOFT
        else -> QuotaLevel.OK
    }
}

/** The current usage for an account (from the managed-DB roll-up; the real roll-up wires in the store phase). */
@com.tneff.cyppieagents.tier.StoreKey("quota_usage")
interface QuotaUsageStore {
    fun usage(accountId: String): QuotaUsage
}

/** In-memory usage (tests / the roll-up seam). */
class InMemoryQuotaUsageStore : QuotaUsageStore {
    private val byAccount = java.util.concurrent.ConcurrentHashMap<String, QuotaUsage>()
    fun set(accountId: String, usage: QuotaUsage) { byAccount[accountId] = usage }
    override fun usage(accountId: String): QuotaUsage = byAccount[accountId] ?: QuotaUsage(0, 0)
}

/**
 * The write-time quota gate for a Free-fallback account (Design §7.2). HARD → throws a **409
 * `free_quota_exceeded`** (reads are NEVER blocked; existing data is NEVER deleted). SOFT → returned so the
 * caller surfaces a banner/CTA. **Fail-SAFE: a usage-read error does NOT wrongly block the user** — it allows
 * the write (fail-open) and logs; the periodic sweep + the hard cap on the next successful read backstop it.
 */
class QuotaGuard(
    private val usage: QuotaUsageStore,
    private val enforcer: QuotaEnforcer = QuotaEnforcer(),
) {
    private val log = LoggerFactory.getLogger("tier.quota")

    fun enforceWrite(accountId: String): QuotaLevel {
        val level = runCatching { enforcer.evaluate(usage.usage(accountId)) }
            .getOrElse {
                log.warn("quota usage read failed for account {} — allowing the write (fail-open; sweep backstops): {}", accountId, it.message)
                QuotaLevel.OK
            }
        if (level == QuotaLevel.HARD) {
            throw ConflictException(
                "free tier storage quota exceeded — connect your own database or upgrade to continue writing",
                code = "free_quota_exceeded",
            )
        }
        return level
    }
}
