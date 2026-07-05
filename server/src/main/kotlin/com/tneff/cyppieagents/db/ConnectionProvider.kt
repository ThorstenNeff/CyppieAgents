package com.tneff.cyppieagents.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * CYP-220 Phase 2b — resolves the [DataSource] a store should use, from its [StoreBinding] (Design §1.2).
 *
 * **One pool per DSN INSTANCE, not per store:** N stores bound to the same `dsnId` share ONE
 * [HikariDataSource] (keyed by `dsnId`, lazily created + cached). A **rebind** (a store switched to a new
 * instance) simply resolves to a different `dsnId` → a different pool; [evictUnreferenced] then closes any
 * pool no binding points at anymore (the "drain A's pool" step, §1.3).
 *
 * Pools are conservative for BYO (small user DBs): a low [maxPoolSize], short connect timeout. The
 * [poolFactory] is injectable so tests exercise the resolution/sharing/rebind/eviction logic WITHOUT a live
 * Postgres (prod default = a real [HikariDataSource]). A store with **no** binding resolves to `null` → the
 * store falls back to its file impl (wired in a later phase).
 */
class ConnectionProvider(
    private val dsns: DsnRegistry,
    private val bindings: BindingRegistry,
    private val poolFactory: (HikariConfig) -> DataSource = { HikariDataSource(it) },
    private val maxPoolSize: Int = 4,
    private val connectionTimeoutMs: Long = 10_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("db.connectionprovider")
    private val lock = Any()
    private val poolByDsn = HashMap<String, DataSource>()

    /** The pooled [DataSource] for a store's bound instance, or `null` if the store is unbound (file fallback). */
    fun forStore(storeKey: String, projectId: String): DataSource? = synchronized(lock) {
        val binding = bindings.binding(storeKey, projectId) ?: return null
        poolFor(binding.dsnId)
    }

    /** The pool for a DSN instance — shared across every store bound to it (lazily built + cached). */
    private fun poolFor(dsnId: String): DataSource = synchronized(lock) {
        poolByDsn.getOrPut(dsnId) {
            val r = dsns.resolve(dsnId)
                ?: throw IllegalStateException("store is bound to unknown DSN '$dsnId'")
            val cfg = HikariConfig().apply {
                jdbcUrl = r.descriptor.jdbcUrl()
                username = r.descriptor.user
                password = r.password // decrypted; lives only in the pool config, never logged
                // the binding's schema is applied per-store later; the instance pool is schema-agnostic here.
                maximumPoolSize = maxPoolSize
                connectionTimeout = connectionTimeoutMs
                poolName = "cyppie-$dsnId"
            }
            log.info("opening connection pool for DSN '{}' ({})", dsnId, r.descriptor.jdbcUrl()) // URL has no credentials
            poolFactory(cfg)
        }
    }

    /**
     * Close + drop any cached pool whose `dsnId` is no longer referenced by ANY binding (call after a rebind /
     * unbind). Returns the number of pools evicted. Idempotent.
     */
    fun evictUnreferenced(): Int = synchronized(lock) {
        val live = bindings.list().map { it.dsnId }.toSet()
        val orphans = poolByDsn.keys.filter { it !in live }
        orphans.forEach { closeQuietly(poolByDsn.remove(it)) }
        orphans.size
    }

    override fun close() = synchronized(lock) {
        poolByDsn.values.forEach { closeQuietly(it) }
        poolByDsn.clear()
    }

    private fun closeQuietly(ds: DataSource?) {
        (ds as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
