package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-474 — the native-loopback flavor selection. With `nativeOidcLoopback=true` the GitHub OIDC start yields the
 * §4 **BrowserHandoff** (system browser + localhost return, H3), not the web **Redirecting** — the one honest
 * fork. VM on an [UnconfinedTestDispatcher] so its launches settle eagerly (CYP-419 lesson).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Cyp474NativeOidcLoopbackTest {

    private val redirect = StubAuthRepository(githubStartResult = { GithubStart.Redirect("https://gh.test/a") })

    @Test
    fun nativeLoopback_startGithub_isBrowserHandoff_notRedirecting() = runTest {
        val vm = AuthViewModel(redirect, nativeOidcLoopback = true, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle() // init checkSession → None → Unauthenticated
        vm.startGithub(); advanceUntilIdle()
        val gh = assertIs<AuthUiState.Unauthenticated>(vm.state.value).github
        val handoff = assertIs<GithubUiState.BrowserHandoff>(gh)
        assertEquals("https://gh.test/a", handoff.url)
    }

    @Test
    fun webFlavor_startGithub_staysRedirecting() = runTest {
        val vm = AuthViewModel(redirect, nativeOidcLoopback = false, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle(); vm.startGithub(); advanceUntilIdle()
        val gh = assertIs<AuthUiState.Unauthenticated>(vm.state.value).github
        assertIs<GithubUiState.Redirecting>(gh) // NOT BrowserHandoff — the web redirect flavor is unchanged
    }
}
