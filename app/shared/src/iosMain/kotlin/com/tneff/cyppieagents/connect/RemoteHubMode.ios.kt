package com.tneff.cyppieagents.connect

/** iOS (stubbed target): remote mode unavailable → hard-off, no factory. */
actual fun remoteHubEnabled(): Boolean = false
actual fun remoteMuxTransportEnabled(): Boolean = false

actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? = null

/** Remote mode is jvm-only in the MVP → no live components on iOS. */
actual fun defaultRemoteComponentsFactory(
    operatorToken: () -> String?,
    passphrasePromptCoordinator: PassphrasePromptCoordinator?,
): RemoteConnectComponentsFactory? = null

/** Remote is jvm-only → the stub CP client (INERT; the gate never mounts here since [remoteHubEnabled] is false). */
actual fun defaultControlPlaneClient(operatorToken: () -> String?): ControlPlaneClient = StubControlPlaneClient()
