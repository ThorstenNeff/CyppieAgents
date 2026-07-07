package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-255 (.4b) / CYP-247.4 — the [RuntimeSuspensionPolicy] LRU + cap decision, tested deterministically
 * with injected suspend/resume actions (no real processes). [UnconfinedTestDispatcher] runs the launched
 * suspend/resume eagerly to first suspension, so the injected fakes complete before the assertions.
 *
 * The load-bearing invariant: beyond the cap, the **least-recently-hot BACKGROUND** project is suspended —
 * NEVER the active one, NEVER a more-recent background one. Mutation (suspend `live.last` instead of the LRU
 * pick, or drop the cap check) → the wrong project (or none) is suspended → these reden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSuspensionPolicyTest {

    private class Fix(cap: Int, scope: CoroutineScope) {
        val suspendedCalls = mutableListOf<String>()
        val resumedCalls = mutableListOf<Pair<String, Set<String>>>()
        val runningAgentsOf = mutableMapOf<String, Set<String>>() // project → the "running" agents suspend stops
        val policy = RuntimeSuspensionPolicy(
            cap = cap,
            scope = scope,
            suspendProject = { pid -> suspendedCalls.add(pid); runningAgentsOf[pid] ?: emptySet() },
            resumeProject = { pid, agents -> resumedCalls.add(pid to agents) },
        )
    }

    @Test
    fun withinCap_nothingSuspended_activeIsHot_othersBackground() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 3, backgroundScope)
        listOf("A", "B", "C").forEach { f.policy.onActivated(it) }
        assertEquals(emptyList(), f.suspendedCalls, "within K=3 → nothing suspended")
        assertEquals(RuntimeState.HOT, f.policy.stateOf("C", "C"), "the last-activated is HOT")
        assertEquals(RuntimeState.BACKGROUND, f.policy.stateOf("B", "C"))
        assertEquals(RuntimeState.BACKGROUND, f.policy.stateOf("A", "C"))
    }

    @Test
    fun beyondCap_suspendsLeastRecentlyHotBackground_notActive() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 3, backgroundScope)
        f.runningAgentsOf["A"] = setOf("a1")
        listOf("A", "B", "C", "D").forEach { f.policy.onActivated(it) } // D is the 4th → cap exceeded
        assertEquals(listOf("A"), f.suspendedCalls, "the LRU background (A) is suspended — NOT active D, NOT recent C")
        assertEquals(RuntimeState.HOT, f.policy.stateOf("D", "D"))
        assertEquals(RuntimeState.BACKGROUND, f.policy.stateOf("C", "D"))
        assertEquals(RuntimeState.BACKGROUND, f.policy.stateOf("B", "D"))
        assertEquals(RuntimeState.SUSPENDED, f.policy.stateOf("A", "D"))
    }

    @Test
    fun reactivateBackground_movesToRecent_doesNotSuspend() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 3, backgroundScope)
        listOf("A", "B", "C").forEach { f.policy.onActivated(it) }
        f.policy.onActivated("A") // re-activate an already-live project → just moves to most-recent
        assertEquals(emptyList(), f.suspendedCalls, "re-activating a live project doesn't grow the live set → no suspend")
        // now activate D → the NEW LRU is B (A was refreshed), so B is suspended
        f.policy.onActivated("D")
        assertEquals(listOf("B"), f.suspendedCalls, "after A refreshed, the LRU is B → B suspended (not A)")
    }

    @Test
    fun reactivateSuspended_resumesExactlyItsStoppedAgents() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 1, backgroundScope) // cap 1 → only the active project stays live
        f.runningAgentsOf["A"] = setOf("a1", "a2")
        f.policy.onActivated("A") // A HOT
        f.policy.onActivated("B") // B HOT → A suspended (its {a1,a2} stopped)
        assertEquals(listOf("A"), f.suspendedCalls)
        assertEquals(RuntimeState.SUSPENDED, f.policy.stateOf("A", "B"))
        f.policy.onActivated("A") // re-enter A → resume exactly {a1,a2}; B is now the LRU → suspended
        assertEquals(listOf("A" to setOf("a1", "a2")), f.resumedCalls, "re-entry resumes exactly the agents A was suspended with")
        assertEquals(listOf("A", "B"), f.suspendedCalls, "B suspended on A's re-entry")
        assertEquals(RuntimeState.HOT, f.policy.stateOf("A", "A"))
        assertEquals(RuntimeState.SUSPENDED, f.policy.stateOf("B", "A"))
    }

    @Test
    fun capZero_disablesSuspension_allStayLive() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 0, backgroundScope)
        listOf("A", "B", "C", "D", "E").forEach { f.policy.onActivated(it) }
        assertEquals(emptyList(), f.suspendedCalls, "cap ≤ 0 → suspension disabled, unbounded background-live")
        assertEquals(RuntimeState.BACKGROUND, f.policy.stateOf("A", "E"), "old projects stay BACKGROUND, never suspended")
    }

    @Test
    fun neverActivatedProject_readsHot_noIndicator() = runTest(UnconfinedTestDispatcher()) {
        val f = Fix(cap = 3, backgroundScope)
        f.policy.onActivated("A")
        assertEquals(RuntimeState.HOT, f.policy.stateOf("fresh", "A"), "a never-activated project → HOT (no indicator), not SUSPENDED")
    }
}
