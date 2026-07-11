package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.ResourceGovernor
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.SpawnDecision
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-442 (Bug/High, deploy-blocker) — the **roster floor** on the CYP-417 [ResourceGovernor]. The governor gates
 * the boot roster too (`BootOrchestrator` boots each config agent via `LifecycleManager.bootAgent` →
 * `rejectIfOverCapacity`), so on a lean box where `estimatedMax < rosterSize` a deploy+restart would reject-spawn
 * seeded agents = **agent loss** (violates the preserve rule). The fix: the gate is `max(estimatedMax, rosterFloor)`,
 * floor = the configured local roster, so the whole roster always boots while NEW spawns above it stay gated — and
 * the reported [estimatedMax] stays the honest hardware value (the pill never lies).
 *
 * ★ Headline tooth: [sevenAgentRoster_bootsFully_evenWhenEstimateBelowRoster] — remove the floor from
 * [ResourceGovernor.admitSpawn] (gate = estimate) → only 3 of 7 boot, 4 land in `failedAgents` → red.
 * ★ Non-vacuity Gegenprobe: [spawnAboveFloor_stillRejected] — if the governor were neutralized (always Admit) the
 * headline tooth would pass vacuously; this reds on always-Admit, proving the floor didn't defang the gate.
 */
class Cyp442GovernorRosterFloorTest {

    private val perAgent = 512L * 1024 * 1024 // the governor's default 512 MiB/agent grain
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    /** A governor that estimates [estimate] agents from hardware, with an explicit roster [floor]. */
    private fun governor(estimate: Int, floor: Int) = ResourceGovernor(
        maxMemoryBytes = { estimate * perAgent },
        availableProcessors = { 4096 }, // never the binding constraint here — memory is
        rosterFloor = { floor },
    )

    // ---- ★ headline tooth: the whole seeded roster boots even when the estimate is below it ----

    @Test
    fun sevenAgentRoster_bootsFully_evenWhenEstimateBelowRoster() {
        val agents = listOf(AgentConfig("po", "PO", Role.PO)) +
            (1..6).map { AgentConfig("w$it", "W$it", Role.WORKER) } // 7 local agents, exactly one PO
        val cfg = PlatformConfig(RepoConfig("git@github.com:org/repo.git", "main"), agents = agents)
        val secrets = Secrets(
            agentTokens = agents.associate { "tok-${it.id}" to it.id },
            operatorToken = "tok-op",
            apiKey = null,
        )
        // estimatedMax = 3 (memory-bound), but the floor = the 7-agent roster → all 7 must boot, none rejected.
        val gov = ResourceGovernor(
            maxMemoryBytes = { 3 * perAgent },
            availableProcessors = { 4096 },
            rosterFloor = { cfg.agents.count { !it.remote } },
        )
        val booted = BootOrchestrator(
            cfg, secrets, WorktreeManager(FakeGit(), Files.createTempDirectory("cyp442-boot").toFile()),
            FakeSpawner(), scope, resourceGovernor = gov,
        ).boot()

        assertEquals(7, booted.bootedAgents.size, "the whole 7-agent roster boots despite estimatedMax=3 (floor)")
        assertTrue(booted.failedAgents.isEmpty(), "no seeded agent is reject-spawned at boot (preserve rule)")
        assertEquals(3, gov.estimatedMax(), "the pill's hardware estimate stays honest (floor doesn't inflate it)")
    }

    // ---- ★ non-vacuity: the governor still fail-closed rejects a spawn ABOVE max(estimate, floor) ----

    @Test
    fun spawnAboveFloor_stillRejected() {
        val g = governor(estimate = 3, floor = 7) // gate = max(3, 7) = 7
        for (current in 0..6) {
            assertIs<SpawnDecision.Admit>(g.admitSpawn(current), "roster slot $current is admitted by the floor")
        }
        val atFloor = g.admitSpawn(7)
        assertIs<SpawnDecision.Reject>(atFloor, "a spawn above the roster floor is still fail-closed rejected")
        assertEquals(7, atFloor.current)
        assertEquals(3, atFloor.estimatedMax, "the reject reports the HONEST hardware estimate, never the floor")
        assertIs<SpawnDecision.Reject>(g.admitSpawn(8), "further over-capacity spawns stay rejected")
    }

    // ---- honesty + advisory-only invariants preserved under the floor ----

    @Test
    fun floor_doesNotInflate_estimatedMax() {
        // The floor is a gate concept only; the pill/capacity.changed estimate is pure hardware (H5).
        assertEquals(3, governor(estimate = 3, floor = 99).estimatedMax())
    }

    @Test
    fun noReliableEstimate_admitsDespiteFloor() {
        // No -Xmx → no estimate → advisory-only: admit everything (the floor never invents a ceiling, H5).
        val g = ResourceGovernor(maxMemoryBytes = { Long.MAX_VALUE }, rosterFloor = { 7 })
        assertIs<SpawnDecision.Admit>(g.admitSpawn(9999))
    }

    @Test
    fun zeroFloor_isPreCyp442_behavior() {
        // floor 0 (default, tests/legacy) → gate = estimate → identical to the CYP-417 estimate-only gate.
        val g = governor(estimate = 2, floor = 0)
        assertIs<SpawnDecision.Admit>(g.admitSpawn(1))
        assertIs<SpawnDecision.Reject>(g.admitSpawn(2))
    }
}
