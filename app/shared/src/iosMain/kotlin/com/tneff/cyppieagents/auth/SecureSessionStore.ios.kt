package com.tneff.cyppieagents.auth

/**
 * CYP-413 — Phase-1 in-memory actual (iOS). R7=session-only. The durable actual is the **iOS Keychain** —
 * **named, not built** here.
 */
actual class SecureSessionStore actual constructor() {
    private var material: SessionMaterial? = null
    actual fun sessionMaterial(): SessionMaterial? = material
    actual fun put(material: SessionMaterial, persistence: Persistence) { this.material = material }
    actual fun clear() { material = null }
}
