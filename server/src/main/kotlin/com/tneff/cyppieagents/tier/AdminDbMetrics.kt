package com.tneff.cyppieagents.tier

import com.tneff.cyppieagents.db.DsnRegistry
import kotlinx.serialization.Serializable

/**
 * CYP-220 Phase 5 — the admin DB-monitor data path (Design §7.4). **Content-free / NO secrets:** a DSN is
 * referenced by its `dsnId` + a **masked host** only — never a password, never a full DSN. Numbers only for
 * connections/storage/quota. Feeds a future operator admin-monitor window (no UI built here).
 */
@Serializable
data class DbInstanceMetrics(
    val dsnId: String,
    val maskedHost: String,
    val tierOrigin: String,
    val activeConnections: Int? = null,
    val poolMax: Int? = null,
    val dbSizeBytes: Long? = null,
)

/** Aggregate Free-fallback footprint on our managed DB — "how much Free load are we carrying" (§7.4). */
@Serializable
data class QuotaAggregate(
    val accountsNearCap: Int = 0,
    val accountsAtCap: Int = 0,
    val totalFallbackBytes: Long = 0,
)

@Serializable
data class DbMetricsSnapshot(
    val instances: List<DbInstanceMetrics>,
    val quota: QuotaAggregate,
)

/** Live pool stats for a DSN instance (from HikariCP) — numbers only. */
data class PoolStat(val activeConnections: Int, val poolMax: Int)

/** The operator admin-monitor data source (§7.4). */
interface DbMetricsProvider {
    fun snapshot(): DbMetricsSnapshot
}

/**
 * Builds the snapshot from the [DsnRegistry] **descriptors** (dsnId + host + tierOrigin — the descriptor holds
 * NO password) + optional live pool/db stats. Because it reads descriptors (never `resolve`, which would decrypt
 * the password), it is **secret-free by construction**. Host is masked; connection/size come from the supplied
 * stats hooks (HikariCP pool + `pg_database_size` in prod; omitted/None in tests).
 */
class DsnRegistryMetricsProvider(
    private val dsns: DsnRegistry,
    private val poolStatsOf: (dsnId: String) -> PoolStat? = { null },
    private val dbSizeOf: (dsnId: String) -> Long? = { null },
    private val quotaAggregate: () -> QuotaAggregate = { QuotaAggregate() },
) : DbMetricsProvider {
    override fun snapshot(): DbMetricsSnapshot {
        val instances = dsns.list().map { d ->
            val pool = poolStatsOf(d.dsnId)
            DbInstanceMetrics(
                dsnId = d.dsnId,
                maskedHost = maskHost(d.host),
                tierOrigin = d.tierOrigin.name,
                activeConnections = pool?.activeConnections,
                poolMax = pool?.poolMax,
                dbSizeBytes = dbSizeOf(d.dsnId),
            )
        }
        return DbMetricsSnapshot(instances, quotaAggregate())
    }

    companion object {
        /** Reduce host exposure in the admin view (not a secret, but minimise): keep the edges, mask the middle. */
        fun maskHost(host: String): String = when {
            host.isBlank() -> "***"
            host.length <= 6 -> "***"
            else -> host.take(2) + "***" + host.takeLast(4)
        }
    }
}
