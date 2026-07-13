package com.tneff.cyppieagents.connect

/** Web (Kotlin/JS): remote mode is unavailable (the jvm Noise stack isn't here) → hard-off, no factory. */
actual fun remoteHubEnabled(): Boolean = false

actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? = null

/** Remote mode is jvm-only in the MVP → no live components on web. */
actual fun defaultRemoteComponentsFactory(operatorToken: () -> String?): RemoteConnectComponentsFactory? = null

/** Remote is jvm-only → the stub CP client (INERT; the gate never mounts here since [remoteHubEnabled] is false). */
actual fun defaultControlPlaneClient(operatorToken: () -> String?): ControlPlaneClient = StubControlPlaneClient()
