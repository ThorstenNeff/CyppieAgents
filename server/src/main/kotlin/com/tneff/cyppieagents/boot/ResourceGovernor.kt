package com.tneff.cyppieagents.boot

/** CYP-417 (S-G) — the capacity decision at the spawn chokepoint. */
sealed interface SpawnDecision {
    /** Enough estimated headroom, OR no reliable estimate (advisory-only — can't gate what we can't ground). */
    object Admit : SpawnDecision

    /** **Fail-closed:** a reliable estimate says the machine is at/over capacity — do NOT spawn. */
    data class Reject(val current: Int, val estimatedMax: Int) : SpawnDecision
}

/**
 * CYP-417 (S-G / D8) — the hub's **resource self-governor** (13-cyppie-hub-architektur §„Ressourcenbewusstsein";
 * productizes the [[oom-gate-tmpfs-host]] OOM lesson). It **estimates** how many agents this machine can safely
 * run from `Runtime.maxMemory()`/`availableProcessors()` and, at the spawn chokepoint, **fail-closed rejects** a
 * spawn that would push past that estimate — a clear rejection instead of an OOM crash.
 *
 * Honesty invariants (UX spec H1/H5): the estimate is a SCHÄTZUNG, not an SLA. When there is no reliable estimate
 * (no `-Xmx` → `maxMemory()` reports `Long.MAX_VALUE`), [estimatedMax] is **null** (never an invented `0`/`∞`,
 * `null≠0`), and [admitSpawn] then **admits** (it never invents a gate it can't ground). The SERVER owns this
 * gate; the client is advisory-only.
 *
 * **Roster floor (CYP-442, preserve rule).** The gate never sits below the configured **boot roster** — a
 * deploy+restart on a lean box must NEVER reject one of the seeded agents (that would silently lose an agent, the
 * [[oom-gate-tmpfs-host]] lesson turned into data-loss). So [admitSpawn]'s gate is `max(estimatedMax, rosterFloor)`:
 * the whole roster always boots, and only NEW spawns *above* that are fail-closed gated. Crucially, [estimatedMax]
 * itself stays the **honest hardware estimate** (the capacity pill/`capacity.changed` show it unchanged, H5) — only
 * the gate uses the floor, so `current > estimatedMax` reads as an honest overcommit, not a lie.
 */
class ResourceGovernor(
    /** Estimated bytes one agent (a `claude` process + PTY + JVM overhead) needs — the estimate's grain, tunable. */
    private val bytesPerAgent: Long = DEFAULT_BYTES_PER_AGENT,
    /** Soft CPU factor: max agents per available processor (the estimate is `min(memory, cpu)`-bound). */
    private val agentsPerCpu: Int = DEFAULT_AGENTS_PER_CPU,
    /** Injectable for tests; default = the JVM's own limits. */
    private val maxMemoryBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
    /**
     * CYP-442 — the configured boot roster's local (non-remote) agent count. The gate is never lower than this, so
     * the seeded roster is always admissible (preserve rule). Single-sourced from `config.agents` (non-remote) at
     * wiring; `0` (default, tests/legacy) means no floor → identical to the pre-CYP-442 estimate-only gate.
     */
    private val rosterFloor: () -> Int = { 0 },
) {
    /**
     * Estimated max agents this machine can safely run, or **null** when there is no reliable estimate (no
     * `-Xmx` → `maxMemory()==Long.MAX_VALUE`, "unbounded" → we refuse to invent a number). `min(memory, cpu)`,
     * at least 1 when an estimate exists.
     */
    fun estimatedMax(): Int? {
        val mem = maxMemoryBytes()
        if (mem == Long.MAX_VALUE || mem <= 0) return null // no -Xmx / unknown → no reliable estimate (null≠0)
        val byMem = (mem / bytesPerAgent).toInt()
        val byCpu = availableProcessors() * agentsPerCpu
        return maxOf(1, minOf(byMem, byCpu))
    }

    /**
     * Fail-closed admission for a new spawn given the [current] live agent count. Rejects ONLY when a reliable
     * estimate says the machine is at/over capacity **and** the count is already past the boot roster; with no
     * estimate it admits (advisory-only, H5). The gate is `max(estimatedMax, rosterFloor)` (CYP-442) so a seeded
     * agent is never rejected, but the reported [SpawnDecision.Reject.estimatedMax] stays the honest hardware
     * estimate (never the floor) — the pill/event never lie. Server-owned.
     */
    fun admitSpawn(current: Int): SpawnDecision {
        val estimate = estimatedMax() ?: return SpawnDecision.Admit // no reliable estimate → advisory-only (H5)
        val gate = maxOf(estimate, rosterFloor())                   // CYP-442: never gate below the boot roster
        return if (current >= gate) SpawnDecision.Reject(current, estimate) else SpawnDecision.Admit
    }

    private companion object {
        const val DEFAULT_BYTES_PER_AGENT = 512L * 1024 * 1024 // 512 MiB/agent (claude + PTY + overhead)
        const val DEFAULT_AGENTS_PER_CPU = 2
    }
}
