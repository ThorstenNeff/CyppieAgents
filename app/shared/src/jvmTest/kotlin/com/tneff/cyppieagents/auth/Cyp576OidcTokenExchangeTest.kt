package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-576 — native API-flow OIDC token-exchange + the P1 security/robustness floor. These teeth pin the load-bearing
 * pieces of the desktop-login fix: the two wire captures ([parseKratosExchangeInitCode], [parseLoopbackParam]), the VM
 * orchestration that redeems BOTH halves, the `state`-nonce rejection, and the handoff watchdog. The HTTP itself is
 * the injected OS boundary (exercised live — the 4th leg). Each tooth names the wrong implementation it reddens.
 */
class Cyp576OidcTokenExchangeTest {

    // --- init half: the session_token_exchange_code from the API login/init response ---

    @Test
    fun initCode_parsedFromApiFlowResponse_absentIsNull() {
        val withCode = """{"id":"flow-1","session_token_exchange_code":"abcDEF0123456789","ui":{}}"""
        assertEquals("abcDEF0123456789", parseKratosExchangeInitCode(withCode))
        // Reddening mutation: point the parser at a different key (e.g. "id") ⇒ this returns "flow-1" ⇒ red.
        assertNull(parseKratosExchangeInitCode("""{"id":"flow-1","ui":{}}"""), "no exchange code ⇒ null (not armed)")
        assertNull(parseKratosExchangeInitCode("""{"session_token_exchange_code":""}"""), "blank ⇒ null")
    }

    // --- loopback query params: the return_to_code AND the state nonce ---

    @Test
    fun loopbackParams_extractCodeAndState_amongOthers_andDecode() {
        assertEquals("XYZ", parseLoopbackCode("state=st8&code=XYZ&flow=f1"))
        // Must key on the exact name, not position: "return the first value" ⇒ code returns "st8" ⇒ red.
        assertEquals("st8", parseLoopbackParam("state=st8&code=XYZ", "state"))
        assertEquals("a/b+c", parseLoopbackParam("code=a%2Fb%2Bc", "code"), "percent-decoded")
        assertNull(parseLoopbackCode("state=only&error=access_denied"), "cancel (?error, no code) ⇒ null")
        assertNull(parseLoopbackParam(null, "code"), "no query ⇒ null")
    }

    // --- VM orchestration: hold init half + nonce at startGithub, redeem with the matching loopback return ---
    // UnconfinedTestDispatcher(testScheduler) runs launches eagerly (the stub never suspends) while still honouring
    // virtual time for the watchdog delay — so no advanceUntilIdle dance, and no viewModelScope/setMain suite-hang.

    @Test
    fun nativeReturn_matchingState_redeemsHeldInitCodeWithReturnCode_thenVerified() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var exchanged: Pair<String, String>? = null
        val stub = StubAuthRepository(
            sessionState = SessionState.None,
            githubStartResult = { GithubStart.Redirect("https://github.test/authorize", initCode = "INIT-64") },
            githubTokenExchangeResult = { init, ret -> exchanged = init to ret; SessionState.Verified(UserTier.OPERATOR) },
        )
        val vm = AuthViewModel(stub, nativeOidcLoopback = true, scope = scope, newOidcState = { "NONCE-1" })
        vm.startGithub()
        val handoff = vm.state.value
        assertTrue(
            handoff is AuthUiState.Unauthenticated && handoff.github is GithubUiState.BrowserHandoff,
            "startGithub hands off to the browser and holds the init half + nonce",
        )
        vm.onGithubReturn(code = "RET-256", state = "NONCE-1") // the loopback echoes our nonce
        // The init half came from startGithub; the return half from the loopback; the nonce matched ⇒ BOTH redeemed.
        // Reddening mutation: onGithubReturn ignores `code`/calls session() ⇒ exchanged stays null ⇒ red.
        assertEquals("INIT-64" to "RET-256", exchanged, "held init code + loopback return code redeemed together")
        assertEquals(AuthUiState.Verified(UserTier.OPERATOR), vm.state.value)
    }

    @Test
    fun nativeReturn_wrongState_rejected_noExchange_error() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var exchangeCalls = 0
        val stub = StubAuthRepository(
            sessionState = SessionState.None,
            githubStartResult = { GithubStart.Redirect("https://github.test/authorize", initCode = "INIT-64") },
            githubTokenExchangeResult = { _, _ -> exchangeCalls++; SessionState.Verified(UserTier.OPERATOR) },
        )
        val vm = AuthViewModel(stub, nativeOidcLoopback = true, scope = scope, newOidcState = { "NONCE-1" })
        vm.startGithub()
        vm.onGithubReturn(code = "RET-256", state = "WRONG-NONCE") // a foreign/forged callback (or bind-fail null)
        // Security floor: a state mismatch MUST NOT exchange — reddening mutation: drop the nonce check ⇒ exchange
        // runs ⇒ exchangeCalls==1 ⇒ red.
        assertEquals(0, exchangeCalls, "a callback that doesn't echo our nonce is rejected — never exchanged")
        val s = vm.state.value
        assertTrue(s is AuthUiState.Unauthenticated && s.github is GithubUiState.Error, "rejected ⇒ retry-able Error")
    }

    @Test
    fun handoffTimeout_breaksTheHang_intoRetryableError() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val stub = StubAuthRepository(
            sessionState = SessionState.None,
            githubStartResult = { GithubStart.Redirect("https://github.test/authorize", initCode = "INIT-64") },
        )
        val vm = AuthViewModel(
            stub, nativeOidcLoopback = true, scope = scope, newOidcState = { "NONCE-1" }, handoffTimeoutMs = 1_000L,
        )
        vm.startGithub()
        assertTrue((vm.state.value as AuthUiState.Unauthenticated).github is GithubUiState.BrowserHandoff)
        advanceTimeBy(1_001L) // no loopback return arrives (bind failure / abandoned tab)
        // Reddening mutation: no watchdog ⇒ stays BrowserHandoff forever ⇒ red (the CYP-578 eternal-hang bug).
        val s = vm.state.value
        assertTrue(s is AuthUiState.Unauthenticated && s.github is GithubUiState.Error, "sustained hang ⇒ retry-able Error")
    }
}
