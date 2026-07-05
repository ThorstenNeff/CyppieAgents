@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-229 — the security-critical credentials tooth, in REAL headless Chrome (wasmJsBrowserTest). The fetch wrapper
 * ([installSameOriginCredentials]) must add `credentials:'include'` for a **same-origin** request (→ the
 * `ory_kratos_session` cookie rides the `/api` reads) but leave a **cross-origin** fetch UNCHANGED — forcing
 * credentials cross-origin would leak the session cookie to a foreign origin (CSRF / cookie-leak). Uses a fetch spy
 * that records the effective `credentials` the wrapper passes through for each request.
 */
class SameOriginCredentialsTest {

    @Test
    fun sameOrigin_getsIncludeCredentials_crossOrigin_untouched() {
        setupFetchSpy()               // reset guard + install a recording spy as window.fetch
        installSameOriginCredentials() // the CYP-229 wrapper wraps the spy
        triggerSameOrigin()
        triggerCrossOrigin()
        assertEquals("include", spyCredAt(0), "same-origin fetch must carry credentials:'include' (session cookie rides)")
        assertEquals("none", spyCredAt(1), "cross-origin fetch must NOT be forced to include (no session-cookie leak)")
        restoreFetch()
    }
}

private fun setupFetchSpy(): Unit =
    js("(function(){window.__cyppieCredsPatched=false;window.__spyCreds=[];window.__origFetch=window.fetch;window.fetch=function(i,n){window.__spyCreds.push((n&&n.credentials)?n.credentials:'none');return Promise.resolve('x');};})()")

private fun triggerSameOrigin(): Unit = js("(function(){window.fetch(window.location.origin+'/api/agents',{});})()")

private fun triggerCrossOrigin(): Unit = js("(function(){window.fetch('https://evil.example.test/x',{});})()")

private fun spyCredAt(i: Int): String = js("(window.__spyCreds[i]||'none').toString()")

private fun restoreFetch(): Unit = js("(function(){if(window.__origFetch)window.fetch=window.__origFetch;window.__cyppieCredsPatched=false;})()")
