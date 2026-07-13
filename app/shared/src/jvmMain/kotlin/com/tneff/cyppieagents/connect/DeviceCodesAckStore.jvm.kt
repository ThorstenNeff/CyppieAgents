package com.tneff.cyppieagents.connect

import java.util.prefs.Preferences

/**
 * JVM/desktop (the remote-mode target): the ack persists across restarts in `java.util.prefs` — the SAME
 * synchronous, user-scoped node as the theme prefs. This is the reload-safety property GE2 requires: an operator
 * who acknowledged their backup codes stays acknowledged after a relaunch (never re-stranded into the enroll gate).
 */
private val ackPrefs: Preferences = Preferences.userRoot().node("com/tneff/cyppieagents")

actual fun defaultDeviceCodesAckStore(): DeviceCodesAckStore =
    PersistentDeviceCodesAckStore(
        load = { key -> ackPrefs.get(key, null) },
        store = { key, value -> ackPrefs.put(key, value) },
    )
