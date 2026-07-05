package com.tneff.cyppieagents.tier

/** An account's billing tier (Design §7.1). */
enum class Tier { FREE, PAID }

/**
 * §7.3 — the per-store residency marker: whether a store's data MAY live in a user DB, or MUST stay on our infra.
 * Bootstrap/auth stores are `MUST_STAY_HOME` (they point at / gate the user DBs, or ARE the login boundary — see
 * the residual analysis §7.6); the operational stores are `USER_DB_CAPABLE`.
 */
enum class StoreResidency { USER_DB_CAPABLE, MUST_STAY_HOME }

/** The resolved DB placement for a (store, account) (§7.1). */
enum class DbPlacement {
    HOME,                   // MUST_STAY_HOME store — always our infra, EXEMPT from tier/quota
    BYO,                    // the user brought a DB (bound) — their instance, no fallback/quota
    MANAGED,                // managed (Aiven) — Paid default
    MANAGED_QUOTA_FALLBACK, // Free fallback slice on managed, quota-enforced
    BYO_REQUIRED,           // Free, no fallback (toggle off + not grandfathered) — writes require a BYO DB
}

/**
 * §7.3 residency inventory — **FAIL-CLOSED** (CYP-220 Phase 6 Slice 1, P5-finding): a store is offloadable to a
 * user DB ONLY if it is on the **explicit [userDbCapable] opt-in allow-list**; anything else (including an
 * unknown / newly-added / mis-typed store key) is `MUST_STAY_HOME`. So a new store never becomes user-DB-capable
 * by accident — a reviewer must add it to the allow-list deliberately (audited by [inventory]-gate teeth).
 */
object StoreResidencies {
    /** Operational stores explicitly cleared to live on a user DB. Secret stores here (project_config apiKey,
     *  remote_token tokens) are only offloadable because their secrets are SecretCipher-encrypted at rest. */
    private val userDbCapable = setOf(
        "project", "project_config", "remote_token", "agent_override", "channel_share", "avatar_blob",
        "event_log", "agent_events", "report", "session", "delivery",
    )

    /** Bootstrap/auth stores that MUST stay on our infra (they point at / gate the user DBs, or ARE the login
     *  boundary — §7.6). Listed for the inventory gate; NOT the fail-closed default (unknown also stays home). */
    private val mustStayHome = setOf(
        "roles", "account", "dsn_registry", "store_binding", "migration_audit", "mcp_config",
        "free_fallback_toggle", "quota_usage",
    )

    /** The full known-store inventory — every store the codebase has MUST be classified here (inventory gate). */
    val inventory: Set<String> = userDbCapable + mustStayHome

    init {
        require(userDbCapable.intersect(mustStayHome).isEmpty()) {
            "a store is in BOTH residency sets: ${userDbCapable.intersect(mustStayHome)}"
        }
    }

    /** FAIL-CLOSED: only the explicit allow-list is offloadable; everything else stays home. */
    fun of(storeKey: String): StoreResidency =
        if (storeKey in userDbCapable) StoreResidency.USER_DB_CAPABLE else StoreResidency.MUST_STAY_HOME

    fun isUserDbCapable(storeKey: String): Boolean = storeKey in userDbCapable
}

/**
 * §7.1 — resolves the DB placement for `(storeKey, account)`, **fail-closed** (the restrictive branch is the
 * default: a Free account without a fallback and not grandfathered must bring a DB before it can write).
 *
 * - A `MUST_STAY_HOME` store → `HOME` (exempt from tier + quota — never on a user DB).
 * - A bound BYO instance → `BYO` (the user's own DB governs; no fallback/quota).
 * - Paid, no BYO → `MANAGED`.
 * - Free, no BYO → `MANAGED_QUOTA_FALLBACK` if the fallback is enabled OR the account is grandfathered; else
 *   `BYO_REQUIRED`.
 */
object TierPolicy {
    fun resolve(
        storeKey: String,
        tier: Tier,
        hasByoBinding: Boolean,
        freeFallbackEnabled: Boolean,
        grandfathered: Boolean,
    ): DbPlacement {
        if (StoreResidencies.of(storeKey) == StoreResidency.MUST_STAY_HOME) return DbPlacement.HOME
        if (hasByoBinding) return DbPlacement.BYO
        return when (tier) {
            Tier.PAID -> DbPlacement.MANAGED
            Tier.FREE -> if (freeFallbackEnabled || grandfathered) DbPlacement.MANAGED_QUOTA_FALLBACK else DbPlacement.BYO_REQUIRED
        }
    }
}
