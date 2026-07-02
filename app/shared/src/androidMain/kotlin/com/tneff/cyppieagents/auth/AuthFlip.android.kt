package com.tneff.cyppieagents.auth

/**
 * Android has no ambient shell environment, so there is no env to read the auth-live config from — the
 * Android app stays the Dev/Demo **stub** default. Live auth on Android would be wired through a
 * launch-intent seam (like `AndroidLaunchEnv` for the operator token, CYP-151); that is a later, additive
 * extension, out of this flip slice's scope.
 */
actual fun defaultAuthLiveEnv(): AuthLiveEnv = AuthLiveEnv(flag = null, origin = null, proxy = null)
