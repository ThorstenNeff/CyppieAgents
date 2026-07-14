package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — the bundled **real** EFF Large Wordlist asset loads, parses to exactly 7776 unique words, passes the
 * [DicewareGenerator] guard, and generates a valid ≥77-bit passphrase from real words. This is the asset-integration
 * proof (the mechanics are unit-tested with a synthetic list in [DicewareGeneratorTest]); the gate additionally
 * verifies the file's SHA-256 vs the pinned provenance (`THIRD-PARTY-LICENSES.md`).
 */
class EffWordlistIntegrationTest {

    @Test
    fun bundledEffWordlist_isExactly7776UniqueWords() {
        val words = DicewareResources.loadEffLargeWordlist()
        assertEquals(DicewareGenerator.EFF_LARGE_SIZE, words.size, "canonical EFF Large = 7776 words (6^5)")
        assertEquals(DicewareGenerator.EFF_LARGE_SIZE, words.toHashSet().size, "all unique (uniform-entropy prerequisite)")
        assertTrue(words.all { it.isNotBlank() && '\t' !in it && ' ' !in it }, "the word column only (no dice digits, no tabs/spaces)")
        assertEquals("abacus", words.first(), "first canonical word (11111)")
        assertEquals("zoom", words.last(), "last canonical word (66666)")
    }

    @Test
    fun defaultDicewareGenerator_buildsFromRealAsset_andGenerates() {
        val gen = assertNotNull(defaultDicewareGenerator(), "the bundled EFF asset loads + passes the 7776-unique guard")
        val pass = gen.generate()
        assertEquals(5, pass.count { it == ' ' }, "6 real words ⇒ 5 spaces")
        assertTrue(pass.isNotEmpty())
        assertTrue(DicewareGenerator.entropyBits(6) >= 77, "6 words over the real 7776-list ⇒ ≥77 bit")
    }
}
