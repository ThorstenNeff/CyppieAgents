package com.tneff.cyppieagents.connect

/** Android: remote mode deferred (the jvm/desktop Noise assembly isn't wired for Android yet) → hard-off, no factory. */
actual fun remoteHubEnabled(): Boolean = false

actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? = null
