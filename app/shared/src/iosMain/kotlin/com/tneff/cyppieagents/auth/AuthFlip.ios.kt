package com.tneff.cyppieagents.auth

import platform.Foundation.NSProcessInfo

/** iOS: read the auth-live flag + stack URLs from the process env (`simctl` injects them), like ShellConfig.ios. */
actual fun defaultAuthLiveEnv(): AuthLiveEnv {
    val env = NSProcessInfo.processInfo.environment
    fun value(key: String): String? = env[key] as? String
    return AuthLiveEnv(
        flag = value("CYPPIE_AUTH_LIVE"),
        origin = value("CYPPIE_AUTH_ORIGIN"),
        proxy = value("CYPPIE_AUTH_PROXY"),
    )
}
