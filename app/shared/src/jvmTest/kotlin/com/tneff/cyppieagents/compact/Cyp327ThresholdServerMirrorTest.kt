package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-327 (QA tooth) — never-optimistic / CONFIRM-ON-SERVER-VALUE proven with a DIVERGENT server return.
 *
 * QA finding: the shipped never-optimistic teeth (`operatorSetThreshold_writesThroughRepo_serverMirror`,
 * `operatorSetThreshold_raisesTransientConfirm_onServerValue`) drive [StubCompactRepository], whose `setConfig`
 * ECHOES the request (`status.copy(thresholdTokens = config.thresholdTokens)`) → server-value == requested-value.
 * So those teeth CANNOT tell "adopt the SERVER's return" apart from "adopt the drafted request" — vacuous for the
 * exact honesty they name (an optimistic impl that displayed the drafted value would pass them unchanged).
 *
 * This tooth uses a server that ADJUSTS the request (clamps 750k → 600k) — the divergence the echo can't express:
 * a never-optimistic VM must show the SERVER's 600k (both `status.thresholdTokens` and the transient confirm), never
 * the requested 750k. Green on the shipped code; reds an optimistic impl (confirm/status from the drafted value).
 */
class Cyp327ThresholdServerMirrorTest {

    private class ClampingRepo(private val serverValue: Int) : CompactRepository {
        override suspend fun getStatus() =
            CompactStatus(allowed = true, thresholdTokens = 500_000, armed = false, running = false, lastRun = null)

        override suspend fun setConfig(config: CompactConfig) =
            CompactStatus(allowed = config.allowed, thresholdTokens = serverValue, armed = config.allowed, running = false, lastRun = null)
    }

    private fun vm(repo: CompactRepository) =
        CompactViewModel(repo, editable = true, CoroutineScope(Dispatchers.Unconfined)) // Unconfined → settles synchronously

    @Test
    fun operatorSetThreshold_adoptsServerValue_notTheRequest_whenServerAdjusts() {
        val vm = vm(ClampingRepo(serverValue = 600_000)) // the server clamps the requested 750k → 600k
        vm.setThreshold(750_000)
        val s = vm.state.value
        assertEquals(600_000, s.status?.thresholdTokens, "the VM adopts the SERVER-returned threshold, never the requested 750k (never optimistic)")
        assertEquals(600_000, s.thresholdSetConfirm, "the transient confirmation shows the SERVER value, not the drafted request")
    }
}
