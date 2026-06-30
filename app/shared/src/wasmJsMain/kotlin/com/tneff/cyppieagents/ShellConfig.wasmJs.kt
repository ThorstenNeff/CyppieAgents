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

/**
 * Web (Wasm) shell config (CYP-152 — operator-token carry).
 *
 * Production default = [ShellConfig.dev] (local hub on 8787, **no operator token**) — a normally
 * served web app stays a fail-closed participant view. The web has no shell env; the deploy-/launch
 * environment supplies the operator token as a host-injected global (see [rawOperatorToken]), the web
 * analogue of the desktop `OPERATOR_TOKEN` env var / iOS `simctl` env. It is **never baked** into the
 * shipped `.wasm`/`.js` bundle and **never logged**.
 *
 * Security (mirrors iOS CYP-114 / Android CYP-151): **absent OR blank/whitespace-only →
 * `operatorToken = null` → fail-closed** (a set-but-blank global must never read as a valid operator).
 */
actual fun defaultShellConfig(): ShellConfig {
    val base = ShellConfig.dev()
    val operatorToken = rawOperatorToken().takeIf { it.isNotBlank() }
    return if (operatorToken != null) base.copy(operatorToken = operatorToken) else base
}
