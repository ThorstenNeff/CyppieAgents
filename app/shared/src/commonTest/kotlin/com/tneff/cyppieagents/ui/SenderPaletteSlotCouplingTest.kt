package com.tneff.cyppieagents.ui

import com.tneff.cyppieagents.model.SENDER_PALETTE_SIZE
import com.tneff.cyppieagents.model.colorSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA-Sweep (Erwartungswert durch die geprüfte Naht) — die **Kopplung**, die `:core`s `ColorSlotTest` nur annimmt.
 *
 * `ColorSlotTest.slot_isWithinPalette` prüft die Schranke gegen die Konstante [SENDER_PALETTE_SIZE].
 * [SenderPalette.forSender] übergibt aber **`senders.size`**, nicht die Konstante, und indiziert die Liste direkt:
 *
 * ```kotlin
 * senders[colorSlot(id, senders.size)]
 * ```
 *
 * Solange beide Zahlen zufällig übereinstimmen, ist die Schranke im `:core`-Test wahr. **Fallen sie auseinander
 * — jemand entfernt einen Farbton, weil er zu sehr nach Status-Rot aussieht — bleibt der `:core`-Test grün und
 * beweist eine Grenze, die die Produktion nicht benutzt.** Diese Datei hält die zwei Zahlen zusammen.
 *
 * Der Test lebt in `:app:shared`, weil nur hier beide Seiten sichtbar sind: die Konstante in `:core`, die Liste
 * in der Palette.
 */
class SenderPaletteSlotCouplingTest {

    @Test
    fun paletteSizeConstant_matchesTheListTheProductionActuallyIndexes() {
        assertEquals(
            SENDER_PALETTE_SIZE, SenderPalette.swatches.size,
            "SENDER_PALETTE_SIZE und SenderPalette.swatches sind auseinandergelaufen. " +
                "forSender() indiziert mit senders.size — die Schranke in ColorSlotTest prüft dann eine " +
                "Palettengröße, die es nicht gibt.",
        )
    }

    /**
     * Und der Beweis, dass die Kopplung trägt: **jeder** Slot, den [colorSlot] über die echte Palettengröße
     * vergeben kann, ist ein gültiger Index in genau diese Liste. Kein `IndexOutOfBounds` für irgendeine id.
     */
    @Test
    fun everySlotOverTheRealPaletteSize_isAValidIndex() {
        val ids = listOf("alice", "bob", "carol", "po-frontend", "channel-42", "", "äöü", "tester", "backend")
        val size = SenderPalette.swatches.size
        assertTrue(size > 0, "Vorbedingung: eine leere Palette könnte gar keinen Slot tragen")
        for (id in ids) {
            val slot = colorSlot(id, size)
            assertTrue(slot in 0 until size, "slot $slot für '$id' liegt außerhalb der echten Palette (size=$size)")
        }
    }
}
