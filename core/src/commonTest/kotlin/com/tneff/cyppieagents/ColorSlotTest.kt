package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.SENDER_PALETTE_SIZE
import com.tneff.cyppieagents.model.colorSlot
import com.tneff.cyppieagents.model.isPoSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorSlotTest {

    @Test
    fun knownAgents_getFixedSlots() {
        assertEquals(0, colorSlot("frontend"))
        assertEquals(1, colorSlot("backend"))
    }

    @Test
    fun po_isReservedSlot_notASenderIndex() {
        assertTrue(isPoSlot("po"))
        assertFalse(isPoSlot("frontend"))
        assertFalse(isPoSlot("backend"))
    }

    @Test
    fun unknownIds_areDeterministic() {
        assertEquals(colorSlot("alice"), colorSlot("alice"))
        assertEquals(colorSlot("po-frontend"), colorSlot("po-frontend"))
    }

    @Test
    fun slot_isWithinPalette() {
        val ids = listOf("alice", "bob", "carol", "x", "channel-42", "po-backend", "")
        for (id in ids) {
            val slot = colorSlot(id)
            assertTrue(slot in 0 until SENDER_PALETTE_SIZE, "slot $slot out of range for '$id'")
        }
    }

    @Test
    fun customPaletteSize_isRespected() {
        assertTrue(colorSlot("anything", paletteSize = 3) in 0 until 3)
    }
}
