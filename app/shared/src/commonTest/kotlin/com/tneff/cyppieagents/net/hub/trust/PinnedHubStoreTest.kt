package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-478 — the durable pin store's serialization + fail-safe custody. A pin round-trips through the keyed KV;
 * a garbled/wrong-sized stored value **fails safe to `null`** (→ a fresh OOB confirm, never a silent adopt of
 * an unverified key); and both stored + returned keys are defensive copies (a caller can't mutate the pin).
 */
class PinnedHubStoreTest {

    private fun backedStore(backing: MutableMap<String, String>) =
        PersistentPinnedHubStore(
            load = { backing[it] },
            store = { k, v -> backing[k] = v },
            remove = { backing.remove(it) },
        )

    @Test
    fun persistent_roundTripsAndUnpins() {
        val backing = mutableMapOf<String, String>()
        val store = backedStore(backing)
        val key = ByteArray(HUB_STATIC_KEY_SIZE) { it.toByte() }

        assertNull(store.pinnedKey("hub-1"))
        store.pin("hub-1", key)
        assertContentEquals(key, store.pinnedKey("hub-1"))
        assertContentEquals(key, backedStore(backing).pinnedKey("hub-1")) // survives a fresh store over the same KV

        store.unpin("hub-1")
        assertNull(store.pinnedKey("hub-1"))
    }

    @Test
    fun decodePin_failsSafeOnGarbleAndWrongSize() {
        assertNull(decodePin(null))
        assertNull(decodePin(""))
        assertNull(decodePin("!!! not base64 !!!"))
        assertNull(decodePin(Base64.Default.encode(ByteArray(16))))        // wrong size ⇒ null
        val ok = ByteArray(HUB_STATIC_KEY_SIZE) { 7 }
        assertContentEquals(ok, decodePin(Base64.Default.encode(ok)))
    }

    @Test
    fun inMemory_storesAndReturnsDefensiveCopies() {
        val store = InMemoryPinnedHubStore()
        val key = ByteArray(HUB_STATIC_KEY_SIZE) { 5 }
        store.pin("h", key)

        key[0] = 99 // mutate the caller's array after pinning
        assertEquals(5, store.pinnedKey("h")!![0], "stored pin must be a copy, not the caller's array")

        val got = store.pinnedKey("h")!!
        got[0] = 42 // mutate the returned array
        assertEquals(5, store.pinnedKey("h")!![0], "returned pin must be a defensive copy")
    }
}
