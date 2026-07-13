package com.tneff.cyppieagents.connect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-525 GE2 — the durable ack store's load-bearing properties: **reload-safe** (an ack survives a relaunch, so a
 * reload before-or-after ack never strands the operator), **per-hub**, and **fail-safe** (absent/garbled ⇒ NOT
 * acknowledged, so the CONNECTED gate holds). The connect-flow gate + UI wiring build on this.
 */
class DeviceCodesAckStoreTest {

    @Test
    fun inMemory_acknowledge_isPerHub() {
        val s = InMemoryDeviceCodesAckStore()
        assertFalse(s.isAcknowledged("hub-a"), "fail-safe: absent ⇒ not acknowledged (the gate holds)")
        s.acknowledge("hub-a")
        assertTrue(s.isAcknowledged("hub-a"))
        assertFalse(s.isAcknowledged("hub-b"), "ack is per-hub — hub-b is independent")
    }

    @Test
    fun persistent_isReloadSafe_aFreshInstanceOverTheSameStoreStillSeesTheAck() {
        // A shared backing map models the durable key-value primitive; a brand-new store instance over it = a relaunch.
        val backing = mutableMapOf<String, String>()
        val first = PersistentDeviceCodesAckStore(load = { backing[it] }, store = { k, v -> backing[k] = v })
        assertFalse(first.isAcknowledged("hub-a"))
        first.acknowledge("hub-a")
        val afterRelaunch = PersistentDeviceCodesAckStore(load = { backing[it] }, store = { k, v -> backing[k] = v })
        assertTrue(afterRelaunch.isAcknowledged("hub-a"), "the ack survives a reload (GE2 reload-safe)")
    }

    @Test
    fun persistent_failSafe_absentOrGarbledIsNotAcknowledged() {
        val backing = mutableMapOf(ackKey("hub-a") to "garbage")
        val s = PersistentDeviceCodesAckStore(load = { backing[it] }, store = { k, v -> backing[k] = v })
        assertFalse(s.isAcknowledged("hub-a"), "a non-\"true\" stored value fails safe to NOT acknowledged")
        assertFalse(s.isAcknowledged("hub-never-touched"), "an absent key fails safe to NOT acknowledged")
    }
}
