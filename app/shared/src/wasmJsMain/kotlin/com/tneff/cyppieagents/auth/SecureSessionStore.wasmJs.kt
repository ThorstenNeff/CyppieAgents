package com.tneff.cyppieagents.auth

/**
 * CYP-413 — in-memory actual (Kotlin/Wasm). The browser stays in-memory **by construction** (R7): a session
 * secret is never written to `localStorage`; the same-origin `ory_kratos_session` cookie is the browser's session
 * credential, so this store simply holds nothing durable.
 */
actual class SecureSessionStore actual constructor() {
    private var material: SessionMaterial? = null
    actual fun sessionMaterial(): SessionMaterial? = material
    actual fun put(material: SessionMaterial, persistence: Persistence) { this.material = material }
    actual fun clear() { material = null }
}
