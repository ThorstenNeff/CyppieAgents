package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

/**
 * CYP-182 flip — **config-gated** selection of the login-gate [AuthRepository] (client GO 2026-07-02:
 * config-gated over unconditional; zero Dev/Demo blast-radius). The default is the CYP-177
 * [StubAuthRepository]; the live [HttpAuthRepository] is chosen ONLY when the deploy explicitly turns it
 * on via `CYPPIE_AUTH_LIVE` + the stack URLs — read per-target with the `defaultShellConfig()` env-pattern
 * (jvm/ios env, js/wasmJs host global; Android has no ambient env → stays stub, extendable like the
 * operator-token seam). The flag + URLs are endpoints/config, **not secrets** — credentials/API key stay
 * in env and out of the selection (never logged here).
 */

/** Raw, unparsed auth-live env/globals read per target. `null` where absent. */
data class AuthLiveEnv(val flag: String?, val origin: String?, val proxy: String?)

/** Per-target read of the auth-live config (jvm/ios env, js/wasmJs host global, Android → all-null). */
expect fun defaultAuthLiveEnv(): AuthLiveEnv

/**
 * The resolved selection (reviewer-frozen 3 branches): [Stub] the Dev/Demo default (flag absent);
 * [Live] the flag set with complete+valid URLs; [Misconfigured] the flag set but URLs missing/malformed
 * — which is **fail-loud**, NOT a silent stub downgrade (someone who set the flag wants real auth; a silent
 * fallback would run unauthenticated where live was intended — a security surprise).
 */
sealed interface AuthMode {
    data object Stub : AuthMode
    data class Live(val platformBaseUrl: String, val kratosProxyUrl: String) : AuthMode
    data class Misconfigured(val reason: String) : AuthMode
}

/** Thrown at startup for a [AuthMode.Misconfigured] live config — fail-loud, never a silent stub downgrade. */
class AuthConfigException(message: String) : IllegalStateException(message)

/**
 * Resolve the raw [env] into the three distinct branches (see [AuthMode]). `CYPPIE_AUTH_LIVE` is truthy
 * for `1`/`true`/`yes`/`on` (case-insensitive); anything else (incl. absent/blank) → [AuthMode.Stub].
 */
fun resolveAuthMode(env: AuthLiveEnv): AuthMode {
    val on = env.flag?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    if (!on) return AuthMode.Stub // flag absent/blank/falsey → Dev/Demo default
    val origin = env.origin?.trim().orEmpty()
    val proxy = env.proxy?.trim().orEmpty()
    val bad = buildList {
        if (!looksLikeHttpUrl(origin)) add("CYPPIE_AUTH_ORIGIN")
        if (!looksLikeHttpUrl(proxy)) add("CYPPIE_AUTH_PROXY")
    }
    return if (bad.isEmpty()) {
        AuthMode.Live(origin, proxy)
    } else {
        // fail-loud: flag ON but the stack URLs are unusable → do NOT silently fall back to the stub.
        AuthMode.Misconfigured("CYPPIE_AUTH_LIVE is set but ${bad.joinToString(" + ")} is missing or malformed")
    }
}

private fun looksLikeHttpUrl(s: String): Boolean {
    if (!s.startsWith("http://") && !s.startsWith("https://")) return false
    return s.substringAfter("://", "").isNotBlank()
}

/**
 * Select the repository for a resolved [mode]: [AuthMode.Stub]→[StubAuthRepository],
 * [AuthMode.Live]→[HttpAuthRepository] (built by [liveFactory]), [AuthMode.Misconfigured]→throw
 * [AuthConfigException] (fail-loud). [liveFactory] is injectable so the selection can be unit-tested
 * without spinning a real client.
 */
fun authRepositoryFor(
    mode: AuthMode,
    liveFactory: (AuthMode.Live) -> AuthRepository = ::buildLiveAuthRepository,
): AuthRepository = when (mode) {
    AuthMode.Stub -> StubAuthRepository()
    is AuthMode.Live -> liveFactory(mode)
    is AuthMode.Misconfigured -> throw AuthConfigException(mode.reason)
}

/** Build the live [HttpAuthRepository]: a client with a shared cookie jar (recovery-cookie clearing). */
private fun buildLiveAuthRepository(live: AuthMode.Live): AuthRepository {
    val cookieStorage = AcceptAllCookiesStorage()
    val client = HttpClient { install(HttpCookies) { storage = cookieStorage } }
    return HttpAuthRepository(client, live.platformBaseUrl, live.kratosProxyUrl, cookieStorage = cookieStorage)
}
