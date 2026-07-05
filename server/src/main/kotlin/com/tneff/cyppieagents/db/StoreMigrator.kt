package com.tneff.cyppieagents.db

import java.security.MessageDigest

/** A store whose full content can be exported as ordered, canonical rows (for the copy read + the verify). */
interface MigrationSource {
    fun exportRows(): List<ByteArray>
}

/** A migration target: a [MigrationSource] that can ALSO be bulk-replaced with rows (raw, no guards/seed). */
interface MigrationTarget : MigrationSource {
    fun importRows(rows: List<ByteArray>)
}

/** The outcome of a store migration — honest no-orphan reporting (counts + checksum, NO content/secrets). */
data class MigrationReceipt(
    val storeKey: String,
    val projectId: String,
    val sourceRows: Int,
    val targetRows: Int,
    val checksumMatch: Boolean,
    val reboundToDsnId: String,
    val ok: Boolean,
)

/** Thrown when the post-copy verification (row-count OR checksum) fails — the migration is aborted, source retained. */
class MigrationVerifyException(message: String) : Exception(message)

/**
 * CYP-220 Phase 4 — the **generic** store migration engine (Design §4), reusable for ANY store (not
 * projectregistry-specific): **read-only window → copy A→B → row-count AND checksum verify → atomic rebind,
 * with A retained for rollback.** Store-agnostic: it works over [MigrationSource]/[MigrationTarget] canonical
 * rows, so it never needs a store's shape. Every phase is audited (Design §5) with counts + ids only — NO secret
 * values (a DSN password never touches these paths; the jdbcUrl is credential-free).
 *
 * The window is a [BindingState.MIGRATING] on the binding (writes gated + reads from source A — enforced at the
 * store-access layer, deferred to the store-wiring phase / Finding B). The **atomic rebind** is the single
 * `MIGRATING → ACTIVE` flip on success; **rollback** is an `unbind` back to the source, which is NEVER dropped
 * here (a separate explicit decommission drops A).
 */
class StoreMigrator(
    private val bindings: BindingRegistry,
    private val audit: MigrationAudit = MigrationAudit.NONE,
) {
    fun migrate(
        source: MigrationSource,
        target: MigrationTarget,
        storeKey: String,
        projectId: String,
        targetDsnId: String,
        actor: String,
        now: Long = 0L,
    ): MigrationReceipt {
        val fromDsnId = bindings.binding(storeKey, projectId)?.dsnId // null = File source (fallback)
        fun log(phase: MigrationPhase, result: MigrationResult, s: Int = 0, t: Int = 0, cm: Boolean = false, err: String? = null) =
            audit.record(MigrationAuditEntry(actor, now, storeKey, projectId, fromDsnId, targetDsnId, phase, result, s, t, cm, err))

        // Window open: bind to the target in MIGRATING (writes gated; reads still from source A).
        bindings.bind(storeKey, projectId, targetDsnId)
        bindings.setState(storeKey, projectId, BindingState.MIGRATING)
        log(MigrationPhase.WINDOW_OPEN, MigrationResult.OK)
        try {
            // Copy A → B (raw bulk).
            val rows = source.exportRows()
            target.importRows(rows)
            log(MigrationPhase.COPY, MigrationResult.OK, rows.size, rows.size)

            // Verify: row-count AND content checksum (count alone can miss corruption).
            val tRows = target.exportRows()
            val countMatch = rows.size == tRows.size
            val checksumMatch = checksum(rows) == checksum(tRows)
            if (!countMatch || !checksumMatch) {
                log(MigrationPhase.VERIFY, MigrationResult.FAILED, rows.size, tRows.size, checksumMatch, "countMatch=$countMatch checksumMatch=$checksumMatch")
                throw MigrationVerifyException("verify failed: countMatch=$countMatch checksumMatch=$checksumMatch (source=${rows.size}, target=${tRows.size})")
            }
            log(MigrationPhase.VERIFY, MigrationResult.OK, rows.size, tRows.size, true)

            // Atomic rebind: the single flip that makes B live.
            bindings.setState(storeKey, projectId, BindingState.ACTIVE)
            log(MigrationPhase.REBIND, MigrationResult.OK, rows.size, tRows.size, true)
            return MigrationReceipt(storeKey, projectId, rows.size, tRows.size, true, targetDsnId, ok = true)
        } catch (e: Throwable) {
            // Rollback: unbind → the store falls back to the retained source A (never dropped). Fail-closed.
            bindings.unbind(storeKey, projectId)
            log(MigrationPhase.ROLLBACK, MigrationResult.FAILED, err = e.message)
            throw e
        }
    }

    /** Store-agnostic checksum over the ordered rows (each length-prefixed → a bijection with the row list). */
    private fun checksum(rows: List<ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (r in rows) {
            md.update(byteArrayOf((r.size ushr 24).toByte(), (r.size ushr 16).toByte(), (r.size ushr 8).toByte(), r.size.toByte()))
            md.update(r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
