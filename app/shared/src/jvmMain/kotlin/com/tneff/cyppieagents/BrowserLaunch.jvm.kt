package com.tneff.cyppieagents

import java.awt.Desktop
import java.net.URI

/**
 * Open an external URL (the GitHub-OIDC handoff, CYP-185/CYP-474 §4) with a **visible, layered fallback** — the fix
 * for the silent-no-op dogfood blocker: the old `runCatching { if (Desktop.isDesktopSupported()) …browse }` swallowed
 * an unsupported desktop / a throwing `browse()` AND never surfaced the URL, so on a machine where AWT `Desktop` is
 * unavailable (common on Linux/WSL) the browser simply never opened and there was no way to continue the sign-in.
 *
 * [launchExternalUrl] is the PURE ordering (unit-tested): (1) **always** log the URL so it is copy-pasteable no
 * matter what; (2) try [browse]; (3) only if that did not open, try [xdgOpen]. The logged URL is the guaranteed last
 * resort. The real AWT/`xdg-open` side effects live in [openExternalUrlWithFallback] (the untestable OS boundary).
 */
fun launchExternalUrl(
    url: String,
    log: (String) -> Unit,
    browse: (String) -> Boolean,
    xdgOpen: (String) -> Boolean,
) {
    // (1) Always surface the URL first — even if both openers fail, the human can copy-paste it into any browser.
    log("→ Open this URL in your browser to sign in:\n$url")
    // (2) Preferred path: the AWT system-browser handoff. Returns true only if it actually opened.
    if (browse(url)) return
    // (3) Fallback: xdg-open (Linux/XDG). Best-effort; if it also fails the logged URL above is the manual path.
    xdgOpen(url)
}

/**
 * Desktop default wiring for [launchExternalUrl]: stdout log + real AWT `Desktop.browse` (guarded by
 * `isDesktopSupported()` AND the `BROWSE` action so it can't throw an `UnsupportedOperationException`) + an
 * `xdg-open` fallback. Every side effect is `runCatching`-guarded and reports success as a boolean, so a failure
 * degrades to the next layer instead of a silent dead end.
 */
fun openExternalUrlWithFallback(url: String) = launchExternalUrl(
    url,
    log = { println(it) },
    browse = { u ->
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(u)); true
            } else {
                false
            }
        }.getOrDefault(false)
    },
    xdgOpen = { u -> runCatching { ProcessBuilder("xdg-open", u).start(); true }.getOrDefault(false) },
)
