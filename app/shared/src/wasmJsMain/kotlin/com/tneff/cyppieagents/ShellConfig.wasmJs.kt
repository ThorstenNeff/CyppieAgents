package com.tneff.cyppieagents

/**
 * Reads the operator token from the host page's global, returning `""` when it is unset (so the
 * fail-closed path below treats absent exactly like blank). Using `globalThis.<name>` (not a bare
 * identifier) is undefined-safe — an unset global reads as `undefined`, coerced to `""`.
 *
 * The token VALUE is **never** a compiled-in literal: the only string baked into the bundle is the
 * lookup expression / global name. The deploy environment injects the value at serve time via a
 * `<script>` in `index.html`, **before** the app bundle loads — never committed, never in the artifact.
 * (Kotlin/Wasm requires `js()` to be a package-level single-expression body with an explicit type.)
 */
private fun rawOperatorToken(): String = js("(globalThis.CYPPIE_OPERATOR_TOKEN || '').toString()")

/** CYP-188 — the page origin (`window.location.origin`), e.g. `https://app.example.com`. Undefined-safe. */
private fun browserOrigin(): String = js("(window.location.origin || '').toString()")

/**
 * Web (Wasm) shell config (CYP-152 operator-token carry + CYP-188 same-origin base).
 *
 * CYP-188: the base is the **page origin** ([browserOrigin]) — the deployed SPA is served same-origin with the
 * API + Kratos proxy, so the browser `ory_kratos_session` session cookie flows on the shell's data reads/sockets
 * (the P2a browser path). CORS keeps `allowCredentials=false`, so a cross-origin base would drop the cookie →
 * app dead for a logged-in end-user. (Dev serving the SPA cross-origin from a separate API keeps the token/stub
 * path; the session-cookie path needs same-origin, matching production.)
 *
 * The deploy-/launch environment supplies the operator token as a host-injected global (see [rawOperatorToken]),
 * the web analogue of the desktop `OPERATOR_TOKEN` env var. It is **never baked** into the shipped `.wasm`/`.js`
 * bundle and **never logged**; the **public** build injects nothing → `operatorToken = null` → fail-closed
 * participant view. Absent OR blank/whitespace-only → null (a set-but-blank global must never read as operator).
 */
actual fun defaultShellConfig(): ShellConfig =
    ShellConfig.forOrigin(browserOrigin(), rawOperatorToken().takeIf { it.isNotBlank() })
