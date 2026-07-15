package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.net.hub.pool.TunnelLane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-620 minimal-cutover — pins **CAUTION-1 (§4.8.4, non-negotiable):** the transport's CONTROL lane MUST map to
 * [StreamClass.CONTROL] (wire 0). That reserved-priority class IS the CYP-616 break-glass — the lifecycle stop/restart
 * fix that made "Server unreachable" go away. Mapping it to a data class would silently lose the break-glass (the
 * regression the whole CYP-616/619/620 line exists to prevent), so this is load-bearing for the mux datapath.
 */
class MuxedStreamSourceTest {

    @Test
    fun controlLane_mapsToStreamClassZero_breakGlass_CYP620caution1() {
        // Mutant: CONTROL → a data class ⇒ break-glass gone ⇒ RED.
        assertEquals(StreamClass.CONTROL, MuxedStreamSource.laneToStreamClass(TunnelLane.CONTROL))
        assertEquals(0, MuxedStreamSource.laneToStreamClass(TunnelLane.CONTROL).wire, "CONTROL lane → streamClass wire 0 (reserved priority)")
    }

    @Test
    fun dataLane_mapsToADataClass_neverTheReservedControlClass() {
        val dataClass = MuxedStreamSource.laneToStreamClass(TunnelLane.DATA)
        assertTrue(dataClass.wire != 0, "DATA lane → a data class (wire ≠ 0), never the reserved CONTROL(0) break-glass")
        assertTrue(dataClass != StreamClass.CONTROL)
    }
}
