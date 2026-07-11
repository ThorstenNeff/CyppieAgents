package com.tneff.cyppieagents.auth

/**
 * CYP-413 — Phase-1 in-memory actual (JVM/Desktop). R7=session-only. A durable backing (macOS Keychain / Linux
 * libsecret via the Secret Service) is a later actual — **named, not built** here.
 */
actual class SecureSessionStore actual constructor() {
    private var material: SessionMaterial? = null
    actual fun sessionMaterial(): SessionMaterial? = material
    // In-memory only in Phase 1, so `persistence` has no effect yet (DEVICE_SECURE gets hardware backing later).
    actual fun put(material: SessionMaterial, persistence: Persistence) { this.material = material }
    actual fun clear() { material = null }
}
