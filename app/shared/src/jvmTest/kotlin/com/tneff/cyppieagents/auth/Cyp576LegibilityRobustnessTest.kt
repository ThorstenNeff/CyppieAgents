package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-576 follow-on — auth legibility + sign-in robustness (busy-guard · TimedOut · error≠cancel · LoginRequired ·
 * re-probe · loopback server-stop). VM-layer teeth (the render is arbitered on the clean env). Each names the wrong
 * implementation it reddens.
 */
class Cyp576LegibilityRobustnessTest {

    /** A repository whose githubStart both COUNTS invocations and can be held mid-flight (to exercise the async gap). */
    private class CountingRepo(
        private val gate: CompletableDeferred<Unit>? = null,
        val start: GithubStart = GithubStart.Redirect("https://gh.test/a", initCode = "INIT"),
    ) : AuthRepository by StubAuthRepository() {
        var startCalls = 0
        override suspend fun githubStart(returnToState: String?): GithubStart {
            startCalls++
            gate?.await()
            return start
        }
    }

    // --- (2) double-click busy-guard: the in-flight state is set SYNCHRONOUSLY, before the async githubStart ---

    @Test
    fun busyGuard_doubleStart_launchesOnlyOneFlow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = CountingRepo(gate)
        val vm = AuthViewModel(
            repo, nativeOidcLoopback = true,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)), newOidcState = { "n" },
        )
        runCurrent() // boot probe → Unauthenticated
        vm.startGithub()  // sets Starting SYNCHRONOUSLY, queues the flow
        vm.startGithub()  // during the async gap the state is already busy (Starting) → refused
        gate.complete(Unit)
        runCurrent()
        // Reddening mutation: remove the synchronous Starting / the isBusy guard ⇒ the 2nd call launches a 2nd flow
        // (2 loopback listeners collide) ⇒ startCalls == 2 ⇒ red.
        assertEquals(1, repo.startCalls, "a double-click during the in-flight gap launches exactly ONE flow")
    }

    // --- (3) TimedOut: advisory + retry-able + the loopback server is stopped so a retry re-binds (Assist-C1) ---

    @Test
    fun timedOut_keepsUrl_isRetryable_andStopsLoopbackServer() = runTest {
        val vm = AuthViewModel(
            StubAuthRepository(
                sessionState = SessionState.None,
                githubStartResult = { GithubStart.Redirect("https://gh.test/z", initCode = "INIT") },
            ),
            nativeOidcLoopback = true, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            newOidcState = { "n" }, handoffTimeoutMs = 1_000L,
        )
        var serverStopped = false
        vm.startGithub()
        vm.setLoopbackStopper { serverStopped = true } // the desktop host registers the stop-handle after arming
        advanceTimeBy(1_001L)
        val gh = (vm.state.value as AuthUiState.Unauthenticated).github
        assertTrue(gh is GithubUiState.TimedOut && gh.url == "https://gh.test/z", "advisory TimedOut keeps the url")
        assertFalse(gh.isBusy, "TimedOut is retry-able (not busy)")
        // Reddening mutation: don't invoke the stopper on timeout ⇒ port 47472 leaks ⇒ serverStopped false ⇒ red.
        assertTrue(serverStopped, "the loopback server is stopped on timeout so a retry re-binds (Assist-C1/CYP-578)")
    }

    // --- (4) error ≠ cancel: access_denied is a neutral cancel; any other error is a real failure ---

    private fun nativeVm(scope: CoroutineScope) = AuthViewModel(
        StubAuthRepository(sessionState = SessionState.None, githubStartResult = { GithubStart.Redirect("https://gh/a", initCode = "I") }),
        nativeOidcLoopback = true, scope = scope, newOidcState = { "NONCE" },
    )

    @Test
    fun cancel_accessDenied_isCancelled_otherError_isError() = runTest {
        val vm = nativeVm(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.startGithub()
        vm.onGithubReturn(state = "NONCE", error = "access_denied")
        assertTrue((vm.state.value as AuthUiState.Unauthenticated).github is GithubUiState.Cancelled, "access_denied ⇒ neutral Cancelled")

        val vm2 = nativeVm(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm2.startGithub()
        vm2.onGithubReturn(state = "NONCE", error = "server_error")
        // Reddening mutation: map every error → Cancelled ⇒ this real error shows as a cancel ⇒ red.
        assertTrue((vm2.state.value as AuthUiState.Unauthenticated).github is GithubUiState.Error, "a real error ⇒ Error, not Cancelled")
    }

    // --- (4) LoginRequired is a DISTINCT state (regression: was folded into Error) ---

    @Test
    fun loginRequired_isDistinctState_notGenericError() = runTest {
        val vm = AuthViewModel(
            StubAuthRepository(githubStartResult = { GithubStart.LoginRequired }),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.startGithub()
        val gh = (vm.state.value as AuthUiState.Unauthenticated).github
        // Reddening mutation: map LoginRequired → Error ⇒ this is Error, not LoginRequired ⇒ red.
        assertTrue(gh is GithubUiState.LoginRequired, "email collision routes to the distinct LoginRequired state")
    }

    // --- (3) continueAfterVerify re-probe: escape the unverified gate without a logout loop ---

    @Test
    fun continueAfterVerify_nowVerified_reachesDesktop_stillUnverified_stays() = runTest {
        val stub = StubAuthRepository(sessionState = SessionState.Unverified("a@b.co"))
        val vm = AuthViewModel(stub, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        // The boot probe put us on the unverified gate.
        assertTrue(vm.state.value is AuthUiState.AuthedUnverified)
        vm.continueAfterVerify() // still unverified → stays (no logout loop)
        assertTrue(vm.state.value is AuthUiState.AuthedUnverified, "still unverified ⇒ stay on the gate, no logout loop")
        stub.sessionState = SessionState.Verified() // operator verified in another tab
        vm.continueAfterVerify()
        // Reddening mutation: continueAfterVerify logs out / doesn't re-probe ⇒ never reaches Verified ⇒ red.
        assertTrue(vm.state.value is AuthUiState.Verified, "re-probe after verification reaches the desktop")
    }
}
