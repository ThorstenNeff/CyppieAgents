package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus

/**
 * In-memory [CompactRepository] for ungated development and tests until Backend's Milestone-C endpoints land
 * — then [CompactHttpRepository] replaces it as the default with no UI/VM change. Honest by construction:
 *  - **Default gate OFF** (`allowed = false`) — fail-closed, matching [CompactConfig]'s default.
 *  - **`setConfig` is the server mirror:** it returns the updated [CompactStatus] the VM then displays, never
 *    an optimistic client guess. `armed` is derived (gate on + not running) so the stub's status is coherent.
 *  - An optional [denyWrites] models the operator gate (server 403) for the fail-closed test.
 */
class StubCompactRepository(
    initial: CompactStatus = CompactStatus(
        allowed = false,
        thresholdTokens = 500_000,
        armed = false,
        running = false,
        lastRun = null,
    ),
    /** When set, every [setConfig] throws this code (e.g. `operator_required`) — models the server gate. */
    private val denyWrites: String? = null,
) : CompactRepository {

    private var status: CompactStatus = initial

    override suspend fun getStatus(): CompactStatus = status

    override suspend fun setConfig(config: CompactConfig): CompactStatus {
        denyWrites?.let { throw CompactException(it) }
        status = status.copy(
            allowed = config.allowed,
            thresholdTokens = config.thresholdTokens,
            // Armed = the gate is on and no run is in flight (the threshold-watch is live). Idle otherwise.
            armed = config.allowed && !status.running,
            // CYP-329 — mirror the tunable timings back (faithful server mirror). A divergent server (e.g. clamping)
            // is modelled by a dedicated test repo, not here — the honesty tooth uses that, not this echo.
            staggerMs = config.staggerMs,
            roundGapMs = config.roundGapMs,
            roundWindowMs = config.roundWindowMs,
        )
        return status
    }
}
