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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * CYP-443 Slice 2 — the CI-6 „nothing carried across" teardown proven at the OTHER `closeActiveComponents`
 * entry points (Slice 1 covered `backToHubList`). Each tooth turns on the **H-1 decrypted-key zeroize** as the
 * clean single-barrier signal (the connect job's `finally` only closes the session, never the keyHold — so the
 * keyHold clear is reached ONLY through the entry point under test → a single-line mutation of that call site
 * reddens exactly this tooth). Covered here: the `connectRemoteInternal` re-connect path (line ~217) and the F1
 * error-path (line ~311); `onCleared` (line ~440) needs a ViewModelStore so it lives in the jvmTest sibling.
 *
 * Note (measure-first): `selectHub` only transitions from `HubList`, so there is no "switch to a different hub
 * in place" bypass — a hub change goes through `backToHubList` (Slice 1). The distinct call site here is the
 * re-connect / re-arm (a repeated `connectRemote`, and the AC-2 enroll→reconnect) which tears the prior components
 * down via `connectRemoteInternal` before building the new session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp443TeardownEntryPointsTest {

    @Test
    fun reConnect_viaConnectRemoteInternal_zeroizesPreviousDecryptedKeyHold() = runTest {
        val recs = mutableListOf<TdRec>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = makeTeardownVm(scope, recordingFactory(recs))
        vm.start(); advanceUntilIdle(); vm.selectHub(HUB_A); vm.connectRemote(); advanceUntilIdle()
        val first = recs.first().comps
        assertEquals(RemoteConnState.CONNECTED, first.session.state.value.conn, "precondition: hub-A connected")
        (first.keyHold as DecryptedKeyHold).put(SEEDED_KEY.copyOf(), expiresAtMs = Long.MAX_VALUE)
        assertNotNull(first.keyHold!!.get(), "precondition: key held")

        // Re-connect (repeated connectRemote from the RemoteConnecting state) → connectRemoteInternal tears the
        // prior components down BEFORE building the new session (Q5/CI-6, line ~217).
        vm.connectRemote(); advanceUntilIdle()

        assertNull(first.keyHold!!.get(), "CI-6/H-1: a re-connect zeroizes the PRIOR connect's decrypted device key")
        assertEquals(2, recs.size, "a fresh components set is built for the re-connect")
        assertNotSame(recs[0].comps, recs[1].comps, "nothing carried across — a fresh components instance")
        scope.cancel()
    }

    @Test
    fun errorPath_rawThrowInConnectDrive_tearsDownAndZeroizesKeyHold() = runTest {
        val recs = mutableListOf<TdRec>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // seedKeyInFactory: the raw throw fires during connect (before the test could seed), so the key is pre-held.
        val vm = makeTeardownVm(scope, recordingFactory(recs, oob = { TdThrowingOob() }, seedKeyInFactory = true))
        vm.start(); advanceUntilIdle(); vm.selectHub(HUB_A); vm.connectRemote(); advanceUntilIdle()

        val comps = recs.first().comps
        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(RemoteConnState.LOST, s.remote.conn, "F1: a raw throw ends in an honest terminal LOST, not a stuck spinner")
        assertNull(comps.keyHold!!.get(), "CI-6/H-1: the error-path teardown zeroizes the half-built connect's decrypted key")
        assertEquals(true, (comps.enrollConfirm as TdRecordingEnroll).aborted, "and aborts a mid-flight enroll (fail-closed)")
        scope.cancel()
    }
}
