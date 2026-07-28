package com.tneff.cyppieagents.multihub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-865 — the switch-first / ONE-ACTIVE-HUB lifecycle: `switchTo` tears down the OLD connection BEFORE opening the
 * new one (exactly one live at a time; no background connection to an inactive hub), switch-to-active is a no-op (no
 * teardown + re-dial), and `close` tears down. The connector is an injected fake (arming DARK — no real dial).
 */
class ActiveHubConnectionTest {

    /** A fake handle whose `close()` and the connector's `open()` append to a shared log (order-sensitive proof). */
    private class FakeHandle(private val id: String, private val log: MutableList<String>) : HubConnectionHandle<String> {
        override val machine: String = id
        override fun close() { log.add("close:$id") }
    }

    private fun loggingConnector(log: MutableList<String>) = HubConnector<String> { hubId ->
        log.add("open:$hubId")
        FakeHandle(hubId, log)
    }

    @Test
    fun active_isNull_beforeFirstConnect() {
        val ahc = createActiveHubConnection(loggingConnector(mutableListOf()))
        assertNull(ahc.active())
    }

    @Test
    fun switchTo_opensAndBecomesActive() {
        val log = mutableListOf<String>()
        val ahc = createActiveHubConnection(loggingConnector(log))
        ahc.switchTo("a")
        assertEquals(listOf("open:a"), log)
        assertEquals("a", ahc.active()?.hubId)
        assertEquals("a", ahc.active()?.machine)
    }

    @Test
    fun switchTo_tearsDownOld_beforeOpeningNew() {
        val log = mutableListOf<String>()
        val ahc = createActiveHubConnection(loggingConnector(log))
        ahc.switchTo("a")
        ahc.switchTo("b")
        // The OLD hub's connection is closed BEFORE the new one opens — exactly one live connection at a time.
        assertEquals(listOf("open:a", "close:a", "open:b"), log)
        assertEquals("b", ahc.active()?.hubId)
    }

    @Test
    fun switchTo_alreadyActive_isNoOp_noTeardownNoReDial() {
        val log = mutableListOf<String>()
        val ahc = createActiveHubConnection(loggingConnector(log))
        ahc.switchTo("a")
        log.clear()
        ahc.switchTo("a")
        assertTrue(log.isEmpty(), "switch-to-active must NOT tear down + re-dial the live connection")
        assertEquals("a", ahc.active()?.hubId)
    }

    @Test
    fun close_tearsDown_andClearsActive() {
        val log = mutableListOf<String>()
        val ahc = createActiveHubConnection(loggingConnector(log))
        ahc.switchTo("a")
        log.clear()
        ahc.close()
        assertEquals(listOf("close:a"), log)
        assertNull(ahc.active())
    }
}
