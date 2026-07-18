package com.tneff.cyppieagents.connect

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

/**
 * CYP-443 Slice 2 — the CROWN of the teardown matrix: **H-1 key-zeroize on VM destroy**. When the flow's
 * ViewModel is cleared (screen gone / app closed), `onCleared` (`closeActiveComponents` §~440) must zeroize the
 * decrypted operator device key so the crown-jewel Ed25519 bytes never linger GC-reachable past the connection
 * (H-1, `DecryptedKeyHold` §4.4). This is the ONE teardown barrier with **no defence-in-depth** — the connect
 * job's `finally` closes only the session, so a single-line mutation of `onCleared`'s clear call reddens this
 * tooth cleanly. jvmTest because the VM-destroy path is driven through a real [ViewModelStore.clear] (the CYP-249
 * idiom): a stored [ViewModel]'s `onCleared` fires on `clear()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp443OnClearedTeardownTest {

    @Test
    fun onCleared_zeroizesDecryptedDeviceKey_h1() = runTest {
        val recs = mutableListOf<TdRec>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        val vm = ViewModelProvider.create(
            owner,
            viewModelFactory { initializer { makeTeardownVm(scope, recordingFactory(recs)) } },
        )[HubConnectViewModel::class]

        vm.start(); advanceUntilIdle(); vm.selectHub(HUB_A); vm.connectRemote(); advanceUntilIdle()
        val comps = recs.first().comps
        assertEquals(RemoteConnState.CONNECTED, comps.session.state.value.conn, "precondition: hub-A connected")
        (comps.keyHold as DecryptedKeyHold).put(SEEDED_KEY.copyOf(), expiresAtMs = Long.MAX_VALUE)
        assertNotNull(comps.keyHold!!.get(), "precondition: decrypted key is held")

        store.clear() // the ViewModel is destroyed (screen gone / app closed) ⇒ onCleared() fires
        advanceUntilIdle()

        assertNull(
            comps.keyHold!!.get(),
            "CI-6/H-1: VM destroy zeroizes the decrypted device key — never left GC-reachable past the connection",
        )
        scope.cancel()
    }
}
