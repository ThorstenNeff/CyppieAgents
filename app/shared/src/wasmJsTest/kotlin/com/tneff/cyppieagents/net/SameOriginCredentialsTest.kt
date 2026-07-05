@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-229/231 — the security-critical same-origin fetch-hardening teeth, in REAL headless Chrome (wasmJsBrowserTest),
 * via a fetch spy that records what the wrapper ([installSameOriginCredentials]) passes through:
 *  - **CYP-229 (credentials):** a **same-origin** request gets `credentials:'include'` (→ the `ory_kratos_session`
 *    cookie rides); a **cross-origin** fetch is UNCHANGED (forcing credentials cross-origin would leak the cookie).
 *  - **CYP-231 (CSRF):** an **unsafe same-origin** write echoes `cyppie_csrf` in `X-CSRF-Token`; a safe method or a
 *    cross-origin request must NOT carry it (no cross-origin token leak).
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

    /**
     * CYP-231 — the CSRF double-submit tooth. On an unsafe same-origin write the wrapper echoes the JS-readable
     * `cyppie_csrf` cookie in `X-CSRF-Token`; a safe method or a cross-origin request must NOT carry it (no token leak).
     */
    @Test
    fun csrfEcho_onUnsafeSameOrigin_absentOnSafeAndCrossOrigin() {
        setupCsrfSpy("csrf-tok-123")     // set the cookie + a spy recording credentials + X-CSRF-Token
        installSameOriginCredentials()
        triggerPutSameOrigin()           // 0: unsafe + same-origin  → echo
        triggerGetSameOrigin()           // 1: safe   + same-origin  → no echo
        triggerPutCrossOrigin()          // 2: unsafe + cross-origin → no echo
        assertEquals("csrf-tok-123", spyCsrfAt(0), "unsafe same-origin write must echo cyppie_csrf in X-CSRF-Token")
        assertEquals("include", spyCredAt(0), "…and still carry the session cookie")
        assertEquals("none", spyCsrfAt(1), "a SAFE method must NOT carry X-CSRF-Token")
        assertEquals("none", spyCsrfAt(2), "a CROSS-ORIGIN write must NOT carry the CSRF token (no cross-origin token leak)")
        assertEquals("none", spyCredAt(2), "cross-origin stays untouched (no forced credentials)")
        teardownCsrf()
    }
}

private fun setupFetchSpy(): Unit =
    js("(function(){window.__cyppieCredsPatched=false;window.__spyCreds=[];window.__origFetch=window.fetch;window.fetch=function(i,n){window.__spyCreds.push((n&&n.credentials)?n.credentials:'none');return Promise.resolve('x');};})()")

private fun triggerSameOrigin(): Unit = js("(function(){window.fetch(window.location.origin+'/api/agents',{});})()")

private fun triggerCrossOrigin(): Unit = js("(function(){window.fetch('https://evil.example.test/x',{});})()")

private fun spyCredAt(i: Int): String = js("(window.__spyCreds[i]||'none').toString()")

private fun restoreFetch(): Unit = js("(function(){if(window.__origFetch)window.fetch=window.__origFetch;window.__cyppieCredsPatched=false;})()")

private fun setupCsrfSpy(tok: String): Unit =
    js("(function(){window.__cyppieCredsPatched=false;window.__spyCreds=[];window.__spyCsrf=[];document.cookie='cyppie_csrf='+tok+';path=/';window.__origFetch=window.fetch;window.fetch=function(i,n){window.__spyCreds.push((n&&n.credentials)?n.credentials:'none');var cs='none';try{if(n&&n.headers){var h=(n.headers instanceof Headers)?n.headers:new Headers(n.headers);var v=h.get('X-CSRF-Token');if(v)cs=v;}}catch(e){}window.__spyCsrf.push(cs);return Promise.resolve('x');};})()")

private fun triggerPutSameOrigin(): Unit = js("(function(){window.fetch(window.location.origin+'/api/acl',{method:'PUT'});})()")

private fun triggerGetSameOrigin(): Unit = js("(function(){window.fetch(window.location.origin+'/api/agents',{method:'GET'});})()")

private fun triggerPutCrossOrigin(): Unit = js("(function(){window.fetch('https://evil.example.test/api',{method:'PUT'});})()")

private fun spyCsrfAt(i: Int): String = js("(window.__spyCsrf[i]||'none').toString()")

private fun teardownCsrf(): Unit = js("(function(){if(window.__origFetch)window.fetch=window.__origFetch;window.__cyppieCredsPatched=false;document.cookie='cyppie_csrf=;path=/;expires=Thu, 01 Jan 1970 00:00:00 GMT';})()")
