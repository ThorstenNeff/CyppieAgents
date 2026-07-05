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

object StoreResidencies {
    /** Bootstrap/auth stores stay home; everything else is offloadable (§7.2/§7.3/§7.6). */
    private val mustStayHome = setOf(
        "roles", "account", "dsn_registry", "store_binding", "migration_audit", "mcp_config", "free_fallback_toggle",
    )
    fun of(storeKey: String): StoreResidency =
        if (storeKey in mustStayHome) StoreResidency.MUST_STAY_HOME else StoreResidency.USER_DB_CAPABLE
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
