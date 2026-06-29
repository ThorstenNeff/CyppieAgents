package com.tneff.cyppieagents

import platform.Foundation.NSProcessInfo

/**
 * iOS shell config (CYP-114 — verification enablement).
 *
 * Production default = [ShellConfig.dev] (local hub on 8787, **no operator token**) — so a normally
 * launched iOS app stays a fail-closed participant view, exactly as before. iOS has no ambient shell
 * environment, so the values below are read from the process environment **only when supplied at
 * launch** (`simctl launch` injects them via `SIMCTL_CHILD_*`). This is the test/verify seam that lets
 * the iOS sim drive the operator-gated surfaces + WS streams (`/ws/comm`, `/ws/lifecycle`,
 * `/ws/events`) for CYP-68/CYP-69 — it does **not** change the shipped behaviour.
 *
 * Security: the operator token is **never baked** (ShellConfig KDoc) and **never committed** — it is
 * supplied at runtime via env and absent by default. **Absent OR blank/whitespace-only →
 * `operatorToken = null` → fail-closed** (a set-but-blank env var must never read as a valid operator).
 *
 *  - `CYPPIE_HUB_HOST`  (default `localhost`) — the sim reaches the host Mac's `localhost`.
 *  - `CYPPIE_HUB_PORT`  (default `8787`, the CYP-24 port).
 *  - `CYPPIE_OPERATOR_TOKEN` — when present **and non-blank**, the privileged operator token; otherwise
 *    participant-only.
 */
actual fun defaultShellConfig(): ShellConfig {
    val env = NSProcessInfo.processInfo.environment

    // Treat "set but blank/whitespace-only" exactly like "not set": isNotBlank() → null. This keeps the
    // no/blank-env case on the null-operator path below (no accidental non-null operator default).
    fun value(key: String): String? = (env[key] as? String)?.takeIf { it.isNotBlank() }

    val host = value("CYPPIE_HUB_HOST") ?: "localhost"
    val port = value("CYPPIE_HUB_PORT")?.toIntOrNull() ?: 8787
    val base = ShellConfig.dev(host, port)

    // Fail-closed: only a present, non-blank token promotes to operator; everything else stays null.
    val operatorToken = value("CYPPIE_OPERATOR_TOKEN")
    return if (operatorToken != null) base.copy(operatorToken = operatorToken) else base
}
