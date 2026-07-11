package com.tneff.cyppieagents.workspace

import kotlinx.coroutines.flow.Flow

/**
 * CYP-417 (Epic CYP-395 S-G ResourceGovernor) — the **content-free** hub-capacity readout data (H6): only counts.
 * [estimatedMax] is **nullable** — `null` = the hub has not estimated capacity yet (H1/Q3: unknown ⇒ show nothing,
 * never a fabricated "0/0"). The estimate is not a hard SLA (H1), so the a11y copy says "(estimated)".
 */
data class HubCapacity(val current: Int, val estimatedMax: Int?)

/** Full (no headroom) ⇔ the max is **known** AND reached (Q2). An unknown max is never "full". */
val HubCapacity.isFull: Boolean get() = estimatedMax != null && current >= estimatedMax

/**
 * CYP-417 (S-G) — the content-free capacity feed (seams **S-1** / **S-2**). **Stub-first** ([StubHubCapacitySource]);
 * this interface is the **drift-free swap seam** — the live impl decodes Backend's two content-free events off
 * `/ws/events` (PO-confirmed 2026-07-11), one decode line per type:
 *  - **[capacity]** ← the initial **`GET /api/capacity`** snapshot (auth-gated) merged with live
 *    `EventType.CAPACITY_CHANGED` updates (wire `"capacity.changed"`, `Severity.INFO`) — both **server-authoritative**
 *    (the same count the gate uses; PO A2 2026-07-11): `HubCapacity(detail["current"].int, detail["estimatedMax"]?.int)`
 *    (`estimatedMax` absent → `null` → the "N aktiv" no-max readout). The Pill reads THIS, **not** a client-derived
 *    roster count — a client count could lie when the governor's count differs (H5).
 *  - **[rejections]** ← `EventType.SPAWN_REJECTED` (wire `"spawn.rejected"`, `Severity.WARN`) — one emission per
 *    real fail-closed reject.
 * Content-free throughout: only counts + a reject signal, never agent output or a secret (H6).
 */
interface HubCapacitySource {
    /** Latest **server-authoritative** estimated capacity, or `null` while unknown (⇒ the readout is absent). */
    fun capacity(): Flow<HubCapacity?>

    /** Emits once per real server-side fail-closed spawn reject (H2/H5) — drives the overload banner. */
    fun rejections(): Flow<Unit>
}
