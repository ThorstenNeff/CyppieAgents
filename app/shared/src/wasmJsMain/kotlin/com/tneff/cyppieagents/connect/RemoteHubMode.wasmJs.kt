package com.tneff.cyppieagents.connect

/** Web (Kotlin/Wasm): remote mode is unavailable (the jvm Noise stack isn't here) → hard-off, no factory. */
actual fun remoteHubEnabled(): Boolean = false

actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? = null
