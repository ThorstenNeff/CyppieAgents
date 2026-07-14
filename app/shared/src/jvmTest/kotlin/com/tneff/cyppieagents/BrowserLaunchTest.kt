package com.tneff.cyppieagents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-575 — the OIDC browser-launch fallback ordering ([launchExternalUrl]). The dogfood blocker was a SILENT no-op:
 * `browse` failed / was unsupported, nothing opened, and the URL was never surfaced so the human could not sign in.
 * These teeth pin the three guarantees; each names the wrong implementation it reddens.
 */
class BrowserLaunchTest {

    /** (1) The URL is ALWAYS logged (the belt-and-suspenders manual path), and (2) a successful browse does NOT also
     *  fire the fallback — no double-open. Reddening mutation: drop the `log(...)` line ⇒ the first assert goes red;
     *  call `xdgOpen` unconditionally (remove the `if (browse) return`) ⇒ the second goes red. */
    @Test
    fun logsUrlAlways_andBrowseWins_noDoubleOpen() {
        val logged = mutableListOf<String>()
        var xdgCalled = false
        launchExternalUrl(
            "https://auth.example/oidc?state=abc",
            log = { logged.add(it) },
            browse = { true }, // the system browser opened
            xdgOpen = { xdgCalled = true; true },
        )
        assertTrue(logged.any { it.contains("https://auth.example/oidc?state=abc") }, "the URL is always logged")
        assertFalse(xdgCalled, "browse succeeded ⇒ the xdg fallback must NOT also fire (no double-open)")
    }

    /** When browse can't open (unsupported/throwing desktop → false), the xdg-open fallback fires WITH the same URL. */
    @Test
    fun fallsBackToXdgOpen_whenBrowseCannotOpen() {
        val logged = mutableListOf<String>()
        var xdgUrl: String? = null
        launchExternalUrl(
            "https://auth.example/oidc?state=xyz",
            log = { logged.add(it) },
            browse = { false }, // AWT Desktop unavailable / BROWSE unsupported
            xdgOpen = { xdgUrl = it; true },
        )
        assertEquals("https://auth.example/oidc?state=xyz", xdgUrl, "browse failed ⇒ xdg-open fallback with the URL")
        assertTrue(logged.any { it.contains("https://auth.example/oidc?state=xyz") })
    }

    /** Belt-and-suspenders: even when BOTH openers fail, the URL was logged — the human still has a manual path. */
    @Test
    fun stillLogsUrl_whenBothOpenersFail() {
        val logged = mutableListOf<String>()
        launchExternalUrl(
            "https://auth.example/oidc?state=fail",
            log = { logged.add(it) },
            browse = { false },
            xdgOpen = { false },
        )
        assertTrue(
            logged.any { it.contains("https://auth.example/oidc?state=fail") },
            "both openers failed ⇒ the logged URL is the guaranteed manual fallback",
        )
    }
}
