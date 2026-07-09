package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-326 — the compact-window VM is a server-owned mirror.
 *  - load resolves the server [CompactStatus]; a FAILED load holds UNKNOWN (null), never a defaulted idle/off (§3-3);
 *  - the "allowed" toggle is operator-gated (fail-closed no-op) and never optimistic (reflects the server's return).
 */
class CompactViewModelTest {

    private fun vm(repo: CompactRepository, editable: Boolean) =
        CompactViewModel(repo, editable, CoroutineScope(Dispatchers.Unconfined)) // init load settles synchronously

    private class ThrowingRepo : CompactRepository {
        override suspend fun getStatus(): CompactStatus = throw RuntimeException("boom")
        override suspend fun setConfig(config: CompactConfig): CompactStatus = throw RuntimeException("boom")
    }

    @Test
    fun load_resolvesServerStatus() {
        val vm = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = true, running = false)), editable = false)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(true, s.status?.allowed)
        assertEquals(500_000, s.status?.thresholdTokens)
    }

    @Test
    fun operatorToggle_writesThroughRepo_serverMirror() {
        val vm = vm(StubCompactRepository(), editable = true) // default allowed = false
        assertEquals(false, vm.state.value.status?.allowed)
        vm.setAllowed(true)
        assertTrue(vm.state.value.status?.allowed == true, "the checkbox adopts the server's returned allowed=true")
    }

    @Test
    fun nonOperatorToggle_isNoOp_failClosed() {
        val vm = vm(StubCompactRepository(), editable = false)
        vm.setAllowed(true)
        assertEquals(false, vm.state.value.status?.allowed, "a non-operator toggle never writes (fail-closed)")
    }

    @Test
    fun failedLoad_holdsUnknown_notDefaulted() {
        val vm = vm(ThrowingRepo(), editable = false)
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.status, "a failed load holds UNKNOWN (null) — never a defaulted idle/off (§3-3)")
    }

    // --- CYP-327 Feature A: editable threshold (server-mirror, operator-gated) ---

    @Test
    fun operatorSetThreshold_writesThroughRepo_keepsAllowed_serverMirror() {
        val vm = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = true, running = false)), editable = true)
        vm.setThreshold(750_000)
        val s = vm.state.value.status
        assertEquals(750_000, s?.thresholdTokens, "the server-returned new threshold is adopted")
        assertEquals(true, s?.allowed, "a threshold write preserves the allowed gate (only the threshold changes)")
    }

    @Test
    fun nonOperatorSetThreshold_isNoOp_failClosed() {
        val vm = vm(StubCompactRepository(CompactStatus(false, 500_000, armed = false, running = false)), editable = false)
        vm.setThreshold(750_000)
        assertEquals(500_000, vm.state.value.status?.thresholdTokens, "a non-operator threshold write never applies (fail-closed)")
    }

    @Test
    fun operatorSetThreshold_raisesTransientConfirm_onServerValue() {
        val vm = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = true)
        assertNull(vm.state.value.thresholdSetConfirm, "no confirmation before a set")
        vm.setThreshold(750_000)
        assertEquals(750_000, vm.state.value.thresholdSetConfirm, "a server-confirmed set raises the transient INFO confirmation on the server value")
    }
}
