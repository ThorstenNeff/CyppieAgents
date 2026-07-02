package com.tneff.cyppieagents.auth

// Web (JS): the auth-live flag + stack URLs come from host-injected globals (deploy sets them in a
// <script> before the bundle loads — never baked), exactly like the operator-token seam in ShellConfig.js.
// `globalThis.<name>` is undefined-safe (unset → undefined → ""). These are endpoints/config, not secrets.
private fun rawAuthLive(): String = js("(globalThis.CYPPIE_AUTH_LIVE || '').toString()")
private fun rawAuthOrigin(): String = js("(globalThis.CYPPIE_AUTH_ORIGIN || '').toString()")
private fun rawAuthProxy(): String = js("(globalThis.CYPPIE_AUTH_PROXY || '').toString()")

actual fun defaultAuthLiveEnv(): AuthLiveEnv = AuthLiveEnv(
    flag = rawAuthLive().ifBlank { null },
    origin = rawAuthOrigin().ifBlank { null },
    proxy = rawAuthProxy().ifBlank { null },
)
