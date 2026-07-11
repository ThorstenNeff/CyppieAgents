package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-413 (S-I) — the [SecureSessionStore] seam + its [AuthSessionStore] backing. Phase-1 in-memory, so these
 * assert the storage contract + the token↔material mapping; the session-end leak guard is teethed in
 * [HttpAuthRepositoryE2eTest] (it lives in `session()`), and "no regression" is carried by the full gate.
 */
class Cyp413SecureSessionStoreTest {

    @Test
    fun secureStore_put_get_clear() {
        val store = SecureSessionStore()
        assertNull(store.sessionMaterial(), "empty by default")
        store.put(SessionMaterial(kratosSessionToken = "tok"), Persistence.SESSION_ONLY)
        assertEquals("tok", store.sessionMaterial()?.kratosSessionToken)
        store.clear()
        assertNull(store.sessionMaterial(), "clear() wipes all material")
    }

    @Test
    fun secureBacked_mapsTheKratosTokenThroughMaterial() {
        val secure = SecureSessionStore()
        val auth: AuthSessionStore = SecureBackedAuthSessionStore(secure)
        assertNull(auth.sessionToken())
        auth.setSessionToken("kratos-42")
        assertEquals("kratos-42", auth.sessionToken())
        assertEquals("kratos-42", secure.sessionMaterial()?.kratosSessionToken, "the token lives in the secure material")
        auth.setSessionToken(null)
        assertNull(auth.sessionToken())
    }

    @Test
    fun secureBacked_clear_wipesTheWholeBundle_notJustTheTokenField() {
        val secure = SecureSessionStore()
        val auth = SecureBackedAuthSessionStore(secure)
        auth.setSessionToken("t")
        auth.clear()
        // The interface default clear() would leave a non-null SessionMaterial(token=null); the override drops it all
        // (token + any future CP ticket). This distinguishes the two — mutation to the default reddens here.
        assertNull(secure.sessionMaterial(), "clear() drops the entire SessionMaterial")
        assertNull(auth.sessionToken())
    }
}
