package com.tneff.cyppieagents.auth

/** JVM/desktop: read the auth-live flag + stack URLs from the environment (like defaultShellConfig). */
actual fun defaultAuthLiveEnv(): AuthLiveEnv = AuthLiveEnv(
    flag = System.getenv("CYPPIE_AUTH_LIVE"),
    origin = System.getenv("CYPPIE_AUTH_ORIGIN"),
    proxy = System.getenv("CYPPIE_AUTH_PROXY"),
)
