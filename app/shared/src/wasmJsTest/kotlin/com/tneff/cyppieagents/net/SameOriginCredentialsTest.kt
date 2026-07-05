@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-229/231 — the security-critical same-origin fetch-hardening teeth, in REAL headless Chrome (wasmJsBrowserTest),
 * via a fetch spy that records what the wrapper ([installSameOriginCredentials]) passes through:
 *  - **CYP-229 (credentials):** a **same-origin** request gets `credentials:'include'` (→ the `ory_kratos_session`
 *    cookie rides); a **cross-origin** fetch is UNCHANGED (forcing credentials cross-origin would leak the cookie).
 *  - **CYP-231 (CSRF):** an **unsafe same-origin** write echoes `cyppie_csrf` in `X-CSRF-Token`; a safe method or a
 *    cross-origin request must NOT carry it (no cross-origin token leak).
 *
 * CYP-229 fast-follow hardening — two non-vacuous teeth on the wrapper's own guards (each verified to go RED under the
 * matching mutation):
 *  - **Double-install idempotency:** the `__cyppieCredsPatched` flag makes a second [installSameOriginCredentials]
 *    a no-op — `window.fetch` is NOT re-wrapped (removing the guard → the second install replaces the reference → RED).
 *  - **Prefix/suffix-spoof:** the same-origin gate is a strict `origin===origin`, not a hostname substring match. A
 *    `evil-<host>` (fools `endsWith`/`includes`) and a `<host>.evil…` (fools `startsWith`) host are CROSS-origin →
 *    neither credentials nor the CSRF token leak (loosening `===` to a hostname prefix/suffix match → RED).
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

    /**
     * CYP-229 fast-follow — double-install idempotency. Installing the wrapper twice must NOT stack a second layer on
     * `window.fetch`: the `__cyppieCredsPatched` guard makes the second call an early-return no-op, so the fetch
     * reference is unchanged. (Behaviourally a double-wrap is harmless — each transform is idempotent — which is
     * exactly why only a reference-identity check catches it. Remove the guard → the 2nd install rebinds
     * `window.fetch` to a fresh function → the identity assert flips RED.)
     */
    @Test
    fun doubleInstall_isGuarded_secondInstallDoesNotRewrapFetch() {
        setupFetchSpy()
        installSameOriginCredentials()  // #1 — wraps the spy, sets __cyppieCredsPatched
        snapshotFetch(0)                // the single wrapper layer
        installSameOriginCredentials()  // #2 — the guard must make this a no-op
        snapshotFetch(1)
        assertTrue(
            snapshotsEqual(0, 1),
            "a second installSameOriginCredentials() must NOT re-wrap window.fetch (idempotency guard: same fn ref)",
        )
        restoreFetch()
    }

    /**
     * CYP-229 fast-follow — prefix/suffix-spoof. The same-origin gate is `origin===origin` (strict), NOT a hostname
     * prefix/suffix match. Two spoof hosts derived from the real page host — `evil-<host>` (would fool
     * `hostname.endsWith`/`includes`) and `<host>.evil.example.test` (would fool `hostname.startsWith`) — are both
     * CROSS-origin, so an unsafe write to them leaks NEITHER `credentials:'include'` NOR the `cyppie_csrf` token.
     * (Loosen `===` to any hostname substring match → a spoof host slips through the gate → these asserts flip RED.)
     */
    @Test
    fun prefixAndSuffixSpoofHosts_areCrossOrigin_noCredentialOrCsrfLeak() {
        setupCsrfSpy("csrf-tok-spoof")
        installSameOriginCredentials()
        triggerSuffixSpoofPut()   // 0: <host>.evil.example.test  — startsWith(host) is true, but origin !== page origin
        triggerPrefixSpoofPut()   // 1: evil-<host>               — endsWith/includes(host) is true, but origin differs
        assertEquals("none", spyCredAt(0), "a suffix-spoof host (<host>.evil…) is CROSS-origin — no forced credentials")
        assertEquals("none", spyCsrfAt(0), "…and NO CSRF token leak to the suffix-spoof host")
        assertEquals("none", spyCredAt(1), "a prefix-spoof host (evil-<host>) is CROSS-origin — no forced credentials")
        assertEquals("none", spyCsrfAt(1), "…and NO CSRF token leak to the prefix-spoof host")
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

// --- CYP-229 fast-follow: idempotency (fetch-ref snapshots) + prefix/suffix-spoof triggers ---

private fun snapshotFetch(slot: Int): Unit = js("(function(){window.__fetchSnap=window.__fetchSnap||[];window.__fetchSnap[slot]=window.fetch;})()")

private fun snapshotsEqual(a: Int, b: Int): Boolean = js("(window.__fetchSnap[a]===window.__fetchSnap[b])")

// A host that STARTS WITH the real page host ( <host>.evil… ) → fools a naive hostname.startsWith gate; strict origin differs.
private fun triggerSuffixSpoofPut(): Unit = js("(function(){window.fetch(window.location.protocol+'//'+window.location.hostname+'.evil.example.test/api',{method:'PUT'});})()")

// A host that ENDS WITH the real page host ( evil-<host> ) → fools a naive hostname.endsWith/includes gate; strict origin differs.
private fun triggerPrefixSpoofPut(): Unit = js("(function(){window.fetch(window.location.protocol+'//evil-'+window.location.hostname+'/api',{method:'PUT'});})()")
