package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.vault.DecryptedKeyHold
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-443 Slice 3 — [HubConnectViewModel.dispose] is the composition-disposal teardown entry (the gate's
 * `DisposableEffect { onDispose { dispose() } }` calls it, because the VM is NOT ViewModelStore-scoped so
 * `onCleared` never fires in prod). This pins that `dispose()` lands at the same chokepoint: the crown-jewel
 * decrypted device key is zeroized (H-1), the session is torn down, and the pool tunnels are closed — teardown
 * ONLY, with no `backToHubList` reload side-effect. Single-barrier on the keyHold zeroize (mutation-clean).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp443DisposeTeardownTest {

    @Test
    fun dispose_zeroizesDecryptedKeyHold_andTearsDownSessionAndPool() = runTest {
        val recs = mutableListOf<TdRec>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = makeTeardownVm(scope, recordingFactory(recs))
        vm.start(); advanceUntilIdle(); vm.selectHub(HUB_A); vm.connectRemote(); advanceUntilIdle()
        val comps = recs.first().comps
        assertEquals(RemoteConnState.CONNECTED, comps.session.state.value.conn, "precondition: hub-A connected")
        (comps.keyHold as DecryptedKeyHold).put(SEEDED_KEY.copyOf(), expiresAtMs = Long.MAX_VALUE)
        comps.tunnelPool!!.acquire() // one live pooled tunnel
        assertNotNull(comps.keyHold!!.get(), "precondition: decrypted key held")

        vm.dispose(); advanceUntilIdle()

        assertNull(
            comps.keyHold!!.get(),
            "CI-6/H-1: dispose() zeroizes the decrypted device key via the teardown chokepoint (never GC-lingering)",
        )
        assertEquals(RemoteConnState.LOST, comps.session.state.value.conn, "dispose() tears the session down")
        assertTrue(
            recs.first().poolDialer.dialed.isNotEmpty() && recs.first().poolDialer.dialed.all { it.closed },
            "dispose() closes the live pool tunnels (nothing carried across a disposal)",
        )
        scope.cancel()
    }
}
