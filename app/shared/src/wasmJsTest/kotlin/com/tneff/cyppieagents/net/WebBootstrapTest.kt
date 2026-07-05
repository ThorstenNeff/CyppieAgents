@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-243 — the web bootstrap ordering invariant, in REAL headless Chrome (wasmJsBrowserTest): the one non-vacuous,
 * testable fact of an otherwise untestable `main()`. [installCredentialsThenStart] must patch `window.fetch`
 * (install → sets `__cyppieCredsPatched`) BEFORE it runs the UI-start lambda, so the pre-shell auth-probe fetch is
 * already wrapped. Mutation: run `startUi()` before `installSameOriginCredentials()` → the flag is false when the
 * lambda runs → this flips RED.
 */
class WebBootstrapTest {

    @Test
    fun installCredentialsThenStart_installsFetchWrapper_beforeStartingUi() {
        resetPatchedFlag() // save window.fetch + clear __cyppieCredsPatched → a clean before-state
        var patchedWhenUiStarted = false
        installCredentialsThenStart {
            // Runs inside the bootstrap: the wrapper MUST already be installed by now.
            patchedWhenUiStarted = readPatchedFlag()
        }
        assertEquals(
            true,
            patchedWhenUiStarted,
            "installSameOriginCredentials() must run BEFORE the UI start (window.fetch patched first)",
        )
        restoreFetchAndFlag()
    }
}

private fun resetPatchedFlag(): Unit =
    js("(function(){window.__wbSavedFetch=window.fetch;window.__cyppieCredsPatched=false;})()")

private fun readPatchedFlag(): Boolean = js("(window.__cyppieCredsPatched===true)")

private fun restoreFetchAndFlag(): Unit =
    js("(function(){if(window.__wbSavedFetch)window.fetch=window.__wbSavedFetch;window.__cyppieCredsPatched=false;})()")
