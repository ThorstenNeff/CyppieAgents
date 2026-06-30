package com.tneff.cyppieagents

/**
 * Android launch-time config carry (CYP-151 — operator-token seam).
 *
 * Android has no ambient shell environment like the desktop (`System.getenv`) or the iOS simulator
 * (`simctl launch` → `SIMCTL_CHILD_*`). The equivalent injection point is the **launch intent**: the
 * activity reads an extra at `onCreate` and stashes it here, **before** the Compose content is set, so
 * [defaultShellConfig] can pick it up. Maestro drives this via `launchApp: arguments:` (mapped to
 * intent extras), giving the operator-gated surfaces a verifiable path on Android.
 *
 * Security (mirrors the iOS CYP-114 shape): the operator token is **never baked** into the APK — no
 * `BuildConfig` field, no resource literal. It is `null` by default, so a normally launched app is a
 * fail-closed participant view. Only a present, **non-blank** runtime value promotes to operator. The
 * value is never logged.
 */
object AndroidLaunchEnv {
    /**
     * The operator token supplied at launch (intent extra `CYPPIE_OPERATOR_TOKEN`), or `null` when
     * absent/blank. Set once by the entry activity before composition; read by [defaultShellConfig].
     */
    @Volatile
    var operatorToken: String? = null
}
