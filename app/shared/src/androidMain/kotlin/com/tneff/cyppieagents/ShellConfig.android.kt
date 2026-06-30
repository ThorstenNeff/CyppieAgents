package com.tneff.cyppieagents

/**
 * Android shell config (CYP-151 — operator-token carry).
 *
 * Production default = [ShellConfig.dev] (local hub on 8787, **no operator token**) — a normally
 * launched Android app stays a fail-closed participant view. Android has no ambient shell environment,
 * so the operator token is read from [AndroidLaunchEnv], which the entry activity populates from the
 * launch intent **before** composition (`simctl`-equivalent seam for Android; Maestro injects it via
 * `launchApp: arguments:`). This is the verify/operator seam — it does **not** bake anything into the
 * shipped APK.
 *
 * Security (mirrors iOS CYP-114): the operator token is **never baked** (ShellConfig KDoc) and
 * **absent OR blank/whitespace-only → `operatorToken = null` → fail-closed** (a set-but-blank value
 * must never read as a valid operator). The token is never logged.
 */
actual fun defaultShellConfig(): ShellConfig {
    val base = ShellConfig.dev()

    // Fail-closed: only a present, non-blank token promotes to operator; everything else stays null.
    val operatorToken = AndroidLaunchEnv.operatorToken?.takeIf { it.isNotBlank() }
    return if (operatorToken != null) base.copy(operatorToken = operatorToken) else base
}
