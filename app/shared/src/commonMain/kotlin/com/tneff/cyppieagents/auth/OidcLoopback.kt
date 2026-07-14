package com.tneff.cyppieagents.auth

/** CYP-474/CYP-576 — the RFC 8252 loopback redirect port. Single source of truth: the desktop host binds this
 *  ([OIDC_LOOPBACK_CALLBACK_URL] listener) AND the API-flow `return_to` uses it, so they can never drift. */
const val OIDC_LOOPBACK_PORT: Int = 47472

/** The fixed loopback callback URL — the API-flow `return_to` and the Kratos `allowed_return_urls` entry (§4.1). */
const val OIDC_LOOPBACK_CALLBACK_URL: String = "http://127.0.0.1:$OIDC_LOOPBACK_PORT/callback"

/**
 * CYP-576 — extract the `return_to_code` from the RFC 8252 loopback callback's query string. After the browser
 * completes the GitHub round-trip, Kratos redirects to `http://127.0.0.1:47472/callback?code=<return_to_code>`;
 * the desktop host's `armLoopbackListener` calls this on the raw query and hands the code to
 * `AuthViewModel.onGithubReturn(code)`, which redeems it (with the init code) at `/sessions/token-exchange`.
 *
 * Pure + `public` (the desktop `main.kt` is in another module) so the code-capture is unit-tested without a live
 * loopback. Tolerant: `null`/blank query, `code` absent, `code` among other params, and a percent-encoded value
 * all resolve honestly (a missing code ⇒ `null` ⇒ the VM reports "no session", never a silent wrong credential).
 */
fun parseLoopbackCode(rawQuery: String?): String? = parseLoopbackParam(rawQuery, "code")

/**
 * CYP-576 P1 — extract a single named query param ([name]) from the loopback callback (the `code` return-half and
 * the `state` nonce both come this way). Same tolerance as [parseLoopbackCode]: missing/blank → null, keyed by exact
 * name (not position), percent-decoded value.
 */
fun parseLoopbackParam(rawQuery: String?, name: String): String? {
    if (rawQuery.isNullOrBlank()) return null
    return rawQuery.split('&')
        .firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@firstNotNullOfOrNull null
            if (pair.substring(0, eq) != name) return@firstNotNullOfOrNull null
            pair.substring(eq + 1).let(::percentDecode).ifBlank { null }
        }
}

/** Minimal `application/x-www-form-urlencoded` decode for a single query value (`%XX` + `+`→space). Best-effort:
 *  a malformed `%XX` is left verbatim rather than throwing (the token-exchange rejects a bad code fail-closed). */
private fun percentDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        when (val c = s[i]) {
            '+' -> { out.append(' '); i++ }
            '%' -> {
                val hex = s.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hex != null && lo != null) { out.append(((hex shl 4) or lo).toChar()); i += 3 }
                else { out.append(c); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}
