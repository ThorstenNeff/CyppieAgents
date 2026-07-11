package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-417 (S-G) — the hub's estimated capacity, the server-authoritative source for the capacity pill
 * (`GET /api/capacity`). Content-free: only the counters `admitSpawn` gates on. [estimatedMax] is **null**
 * when the hub has no reliable estimate (no `-Xmx` → unbounded memory) — the pill then shows "N active"
 * without a max (`null≠0`, never an invented `0`). Same source as the live `capacity.changed` event, so the
 * mount-time read and the live updates cannot diverge.
 */
@Serializable
data class Capacity(
    /** Currently RUNNING agents on this machine. */
    val current: Int,
    /** Estimated max agents this machine can safely run, or null when there is no reliable estimate. */
    val estimatedMax: Int? = null,
)
