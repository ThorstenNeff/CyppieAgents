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
 */
class ResourceGovernor(
    /** Estimated bytes one agent (a `claude` process + PTY + JVM overhead) needs — the estimate's grain, tunable. */
    private val bytesPerAgent: Long = DEFAULT_BYTES_PER_AGENT,
    /** Soft CPU factor: max agents per available processor (the estimate is `min(memory, cpu)`-bound). */
    private val agentsPerCpu: Int = DEFAULT_AGENTS_PER_CPU,
    /** Injectable for tests; default = the JVM's own limits. */
    private val maxMemoryBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
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
     * estimate says the machine is at/over capacity; with no estimate it admits (advisory-only, H5). Server-owned.
     */
    fun admitSpawn(current: Int): SpawnDecision {
        val max = estimatedMax() ?: return SpawnDecision.Admit
        return if (current >= max) SpawnDecision.Reject(current, max) else SpawnDecision.Admit
    }

    private companion object {
        const val DEFAULT_BYTES_PER_AGENT = 512L * 1024 * 1024 // 512 MiB/agent (claude + PTY + overhead)
        const val DEFAULT_AGENTS_PER_CPU = 2
    }
}
