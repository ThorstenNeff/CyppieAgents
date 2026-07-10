package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.SENDER_PALETTE_SIZE
import com.tneff.cyppieagents.model.colorSlot
import com.tneff.cyppieagents.model.isPoSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * Ersetzt den früheren `unknownIds_areDeterministic`, der `colorSlot("alice") == colorSlot("alice")`
     * prüfte: **Eine reine Funktion liefert im selben Lauf zwangsläufig denselben Wert.** Der Test konnte
     * nicht rot werden — außer jemand hätte `Random` eingebaut. Sein Erwartungswert kam aus genau der Naht,
     * die er prüfen sollte.
     *
     * Diese Sollwerte sind **unabhängig** nach der FNV-1a-Spezifikation gerechnet (offset basis 2166136261,
     * prime 16777619, über die UTF-8-Bytes), nicht aus [colorSlot] gezogen. Erst damit ist das Versprechen
     * des KDoc — *„stable across sessions and clients"* — überhaupt eine prüfbare Aussage.
     *
     * `commonTest` läuft auf JVM, JS **und** wasm: derselbe Sollwert auf jedem Target ist die einzige Stelle,
     * an der „clients färben identisch" wirklich gemessen wird.
     */
    @Test
    fun unknownIds_arePinnedToTheFnv1aSlot_notMerelyRepeatable() {
        assertEquals(7, colorSlot("alice"))
        assertEquals(4, colorSlot("bob"))
        assertEquals(2, colorSlot("carol"))
        assertEquals(3, colorSlot("po-frontend"))
        assertEquals(5, colorSlot("po-backend"))
        assertEquals(5, colorSlot("channel-42"))
        assertEquals(6, colorSlot("tester"))
        assertEquals(5, colorSlot(""), "die leere id darf nicht abstürzen und nicht wandern")
    }

    /** Der Modulus muss der Palettengröße folgen — nicht bloß irgendein Wert in Reichweite sein. */
    @Test
    fun customPaletteSize_pinsTheSlot_notJustItsRange() {
        assertEquals(5, colorSlot("alice", paletteSize = 6))
        assertEquals(2, colorSlot("bob", paletteSize = 6))
        assertEquals(1, colorSlot("", paletteSize = 6))
    }

    /**
     * Pinnt die **UTF-8-Byte-Basis** (`encodeToByteArray`). Ein Wechsel auf `String.hashCode()` — der über
     * UTF-16-Einheiten läuft — bliebe für reine ASCII-ids unentdeckt und würde erst bei einem Umlaut-Agentennamen
     * auffallen, dann aber pro Plattform verschieden.
     */
    @Test
    fun nonAsciiIds_hashOverUtf8Bytes() {
        assertEquals(0, colorSlot("äöü"))
        assertEquals(7, colorSlot("агент"))
    }

    /**
     * Der Guard `if (it < paletteSize)`: Eine **bekannte** id mit fixem Slot 1 darf in eine Palette der Größe 1
     * nicht ihren Fixslot zurückgeben — sonst indiziert der Aufrufer daneben. Heute ist der Zweig unerreichbar
     * (`senders.size == SENDER_PALETTE_SIZE == 8`), aber `SenderPalette.forSender` übergibt `senders.size`, nicht
     * die Konstante: **Sobald ein Farbton entfällt, wird dieser Guard tragend.**
     */
    @Test
    fun knownSlotOutsideASmallPalette_fallsBackToTheHash() {
        assertEquals(0, colorSlot("backend", paletteSize = 1))
        assertEquals(0, colorSlot("frontend", paletteSize = 1))
        assertTrue(colorSlot("backend", paletteSize = 2) in 0 until 2)
    }

    /** `require(paletteSize > 0)` — eine Palettengröße 0 ist ein Programmierfehler, kein Slot 0. */
    @Test
    fun nonPositivePaletteSize_isRejected() {
        assertFailsWith<IllegalArgumentException> { colorSlot("alice", paletteSize = 0) }
        assertFailsWith<IllegalArgumentException> { colorSlot("alice", paletteSize = -1) }
    }

    @Test
    fun slot_isWithinPalette() {
        val ids = listOf("alice", "bob", "carol", "x", "channel-42", "po-backend", "")
        for (id in ids) {
            val slot = colorSlot(id)
            assertTrue(slot in 0 until SENDER_PALETTE_SIZE, "slot $slot out of range for '$id'")
        }
    }
}
