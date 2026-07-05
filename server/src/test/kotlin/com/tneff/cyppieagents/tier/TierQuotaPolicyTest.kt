package com.tneff.cyppieagents.tier

import com.tneff.cyppieagents.routing.ConflictException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CYP-220 Phase 5 — the tier→DB policy trio: quota (fail-safe + never-delete-to-fit), the fail-safe toggle, the placement matrix. */
class TierQuotaPolicyTest {

    // ---- QuotaEnforcer / QuotaGuard (§7.2) ----

    @Test fun quota_levels_ok_soft_hard() {
        val e = QuotaEnforcer(FreeQuotaLimits(maxBytes = 1000, softFraction = 0.8))
        assertEquals(QuotaLevel.OK, e.evaluate(QuotaUsage(500, 0)))
        assertEquals(QuotaLevel.SOFT, e.evaluate(QuotaUsage(800, 0)))
        assertEquals(QuotaLevel.SOFT, e.evaluate(QuotaUsage(999, 0)))
        assertEquals(QuotaLevel.HARD, e.evaluate(QuotaUsage(1000, 0)))
        assertEquals(QuotaLevel.HARD, e.evaluate(QuotaUsage(5000, 0)))
    }

    @Test fun quotaGuard_hard_throws409_freeQuotaExceeded_andNeverDeletes() {
        val atCap = QuotaUsage(FreeQuotaLimits().maxBytes, 999)
        val usage = InMemoryQuotaUsageStore().apply { set("acct", atCap) }
        val ex = assertFailsWith<ConflictException> { QuotaGuard(usage).enforceWrite("acct") }
        assertEquals("free_quota_exceeded", ex.code)
        // NEVER delete-to-fit: the guard blocks the WRITE; it never removes existing data (usage unchanged).
        assertEquals(atCap, usage.usage("acct"))
    }

    @Test fun quotaGuard_soft_allowsWrite_returnsSoft() {
        val usage = InMemoryQuotaUsageStore().apply { set("acct", QuotaUsage((FreeQuotaLimits().maxBytes * 0.9).toLong(), 0)) }
        assertEquals(QuotaLevel.SOFT, QuotaGuard(usage).enforceWrite("acct"))
    }

    @Test fun quotaGuard_failSafe_usageReadError_allowsWrite() {
        val throwing = object : QuotaUsageStore { override fun usage(accountId: String) = throw RuntimeException("usage db down") }
        // fail-safe: our metering error must NOT wrongly block the user's write.
        assertEquals(QuotaLevel.OK, QuotaGuard(throwing).enforceWrite("acct"))
    }

    // ---- FreeFallbackToggle (§7.3) ----

    @Test fun toggle_defaultsOn_persistsOff_thenOn() {
        val f = Files.createTempFile("toggle", ".json").toFile().also { it.delete() }
        assertTrue(FreeFallbackToggle(f).isEnabled(), "default ON (no file)")
        FreeFallbackToggle(f).setEnabled(false)
        assertFalse(FreeFallbackToggle(f).isEnabled(), "persisted OFF across instances")
        FreeFallbackToggle(f).setEnabled(true)
        assertTrue(FreeFallbackToggle(f).isEnabled())
    }

    @Test fun toggle_failSafe_corruptOrMissing_defaultsOn() {
        val corrupt = Files.createTempFile("toggle-bad", ".json").toFile().apply { writeText("{ not valid json") }
        assertTrue(FreeFallbackToggle(corrupt).isEnabled(), "unreadable → ON (never lock out onboarding)")
        assertTrue(FreeFallbackToggle(null).isEnabled(), "null file → ON")
    }

    // ---- TierPolicy (§7.1) placement matrix ----

    @Test fun tierPolicy_placementMatrix() {
        // MUST_STAY_HOME store → HOME regardless of tier/toggle
        assertEquals(DbPlacement.HOME, TierPolicy.resolve("roles", Tier.FREE, hasByoBinding = false, freeFallbackEnabled = false, grandfathered = false))
        assertEquals(DbPlacement.HOME, TierPolicy.resolve("dsn_registry", Tier.PAID, hasByoBinding = true, freeFallbackEnabled = true, grandfathered = true))
        // a bound BYO instance → BYO (no fallback/quota)
        assertEquals(DbPlacement.BYO, TierPolicy.resolve("events", Tier.FREE, hasByoBinding = true, freeFallbackEnabled = false, grandfathered = false))
        // Paid, no BYO → MANAGED
        assertEquals(DbPlacement.MANAGED, TierPolicy.resolve("events", Tier.PAID, hasByoBinding = false, freeFallbackEnabled = true, grandfathered = false))
        // Free, no BYO, fallback ON → MANAGED_QUOTA_FALLBACK
        assertEquals(DbPlacement.MANAGED_QUOTA_FALLBACK, TierPolicy.resolve("events", Tier.FREE, hasByoBinding = false, freeFallbackEnabled = true, grandfathered = false))
        // Free, no BYO, fallback OFF, NOT grandfathered → BYO_REQUIRED (fail-closed block)
        assertEquals(DbPlacement.BYO_REQUIRED, TierPolicy.resolve("events", Tier.FREE, hasByoBinding = false, freeFallbackEnabled = false, grandfathered = false))
        // Free, no BYO, fallback OFF, GRANDFATHERED → keeps the fallback
        assertEquals(DbPlacement.MANAGED_QUOTA_FALLBACK, TierPolicy.resolve("events", Tier.FREE, hasByoBinding = false, freeFallbackEnabled = false, grandfathered = true))
    }

    @Test fun storeResidency_bootstrapExempt_operationalCapable() {
        listOf("roles", "account", "dsn_registry", "store_binding", "migration_audit", "mcp_config", "free_fallback_toggle")
            .forEach { assertEquals(StoreResidency.MUST_STAY_HOME, StoreResidencies.of(it), "$it must stay home") }
        listOf("events", "agent_events", "project_config", "avatar_blob", "project", "report")
            .forEach { assertEquals(StoreResidency.USER_DB_CAPABLE, StoreResidencies.of(it), "$it offloadable") }
    }
}
