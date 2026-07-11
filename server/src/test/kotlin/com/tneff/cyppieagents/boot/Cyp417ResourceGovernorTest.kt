package com.tneff.cyppieagents.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CYP-417 (S-G) — [ResourceGovernor] capacity estimate + **fail-closed admission** tooth.
 *
 * **[admitSpawn_rejects_at_or_over_estimated_capacity]** is the fail-closed tooth: with a reliable estimate of 2,
 * a spawn at/over 2 is REJECTED (no OOM). Non-vacuity: drop the reject clause in [ResourceGovernor.admitSpawn]
 * (always Admit) → the over-limit spawn is admitted → this test reds. Server owns the gate.
 */
class Cyp417ResourceGovernorTest {

    /** 512 MiB/agent (the default grain), so N agents ≈ N*512 MiB of maxMemory. */
    private val perAgent = 512L * 1024 * 1024

    private fun governor(maxMem: Long, cpus: Int = 64) =
        ResourceGovernor(maxMemoryBytes = { maxMem }, availableProcessors = { cpus })

    @Test
    fun estimatedMax_is_null_when_memory_is_unbounded() {
        // No -Xmx → maxMemory() == Long.MAX_VALUE → no reliable estimate (null≠0/∞, H1).
        assertNull(governor(Long.MAX_VALUE).estimatedMax())
        assertNull(governor(0L).estimatedMax())
    }

    @Test
    fun estimatedMax_is_min_of_memory_and_cpu() {
        // 4 agents' worth of memory, plenty of CPU → memory-bound = 4.
        assertEquals(4, governor(maxMem = 4 * perAgent, cpus = 64).estimatedMax())
        // Lots of memory, 1 CPU (×2 agents/cpu) → cpu-bound = 2.
        assertEquals(2, governor(maxMem = 100 * perAgent, cpus = 1).estimatedMax())
        // Always at least 1 when an estimate exists.
        assertEquals(1, governor(maxMem = perAgent / 4, cpus = 64).estimatedMax())
    }

    @Test
    fun admitSpawn_rejects_at_or_over_estimated_capacity() {
        val g = governor(maxMem = 2 * perAgent, cpus = 64) // estimatedMax = 2
        assertIs<SpawnDecision.Admit>(g.admitSpawn(0), "headroom → admit")
        assertIs<SpawnDecision.Admit>(g.admitSpawn(1), "headroom → admit")
        val atCap = g.admitSpawn(2)
        assertIs<SpawnDecision.Reject>(atCap, "at capacity → fail-closed reject (not OOM)")
        assertEquals(2, atCap.current); assertEquals(2, atCap.estimatedMax)
        assertIs<SpawnDecision.Reject>(g.admitSpawn(3), "over capacity → reject")
    }

    @Test
    fun admitSpawn_admits_when_no_reliable_estimate() {
        // Advisory-only when we can't ground a gate — never invent one (H5).
        assertIs<SpawnDecision.Admit>(governor(Long.MAX_VALUE).admitSpawn(9999))
    }
}
