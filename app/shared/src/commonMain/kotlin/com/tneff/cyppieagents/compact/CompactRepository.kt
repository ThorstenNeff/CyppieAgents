package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus

/**
 * CYP-326 — the compact-orchestration data port. The window is a **server-owned mirror** (§3-3): it reads
 * the live [CompactStatus] and, for an operator, writes the [CompactConfig] (the global "compact allowed"
 * gate + threshold). Unlike the older `ConfigRepository`, the wire types here ARE the real `:core`
 * `@Serializable` DTOs ([CompactStatus]/[CompactConfig]) — so the live client decodes them directly and the
 * stub→real swap is mechanical, no shape drift.
 *
 * Backend endpoints (Milestone C): `GET /api/compact/status` (read-tier) · `POST /api/compact/config`
 * (operator-gated; the server 403s a non-operator, the UI also disables the control fail-closed).
 */
interface CompactRepository {
    /** The live orchestration status (server-owned). A failed read → the VM holds an honest "unknown" (§3-3). */
    suspend fun getStatus(): CompactStatus

    /** Operator-only: persist the gate/threshold; returns the server's new status. Throws [CompactException]
     *  with the server's reason (`operator_required` / `unauthorized`) on rejection. */
    suspend fun setConfig(config: CompactConfig): CompactStatus
}

/**
 * A compact-config write was rejected. [code] is the server's reason — `operator_required` (403) /
 * `unauthorized` (401). The VM keeps the server-mirror state unchanged (never an optimistic flip).
 */
class CompactException(val code: String) : Exception(code)
