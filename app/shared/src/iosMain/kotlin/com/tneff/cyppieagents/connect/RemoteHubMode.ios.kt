package com.tneff.cyppieagents.connect

/** iOS (stubbed target): remote mode unavailable → hard-off, no factory. */
actual fun remoteHubEnabled(): Boolean = false

actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? = null
