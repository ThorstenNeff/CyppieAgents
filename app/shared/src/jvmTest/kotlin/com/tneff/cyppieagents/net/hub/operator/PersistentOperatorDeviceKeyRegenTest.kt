package com.tneff.cyppieagents.net.hub.operator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F3 (post-login silent-swallow fix) — behavior-preservation for the restructured
 * [PersistentOperatorDeviceKey.loadOrGenerate].
 *
 * The fix adds a WARN log when a PRESENT device-key file is unreadable, so the otherwise-silent identity
 * regeneration (→ the hub sees a new key → forced re-enrollment) becomes diagnosable. The regenerate-on-corrupt
 * BEHAVIOR is deliberately UNCHANGED (the fail-closed-vs-regenerate posture is a PO1 security decision, held for an
 * Assist lens). This pins that the restructure still (a) regenerates a valid key on a corrupt present file and
 * (b) persists it so the next launch loads the SAME key — i.e. the log addition didn't alter the contract.
 */
class PersistentOperatorDeviceKeyRegenTest {

    @Test
    fun corruptPresentFile_regeneratesValidKey_andPersistsItStably() {
        val dir = Files.createTempDirectory("cyp-devkey")
        val keyFile = dir.resolve("operator-device.key")
        Files.write(keyFile, byteArrayOf(1, 2, 3, 4, 5)) // present but unreadable (bad arity/format) → the F3 warn path

        val regenerated = PersistentOperatorDeviceKey(keyFile).loadOrGenerate() // corrupt → WARN + regenerate
        assertNotNull(regenerated.private, "a corrupt present file must still yield a usable regenerated key")
        assertNotNull(regenerated.public)

        // Persisted + stable: a fresh instance on the same file now LOADS the regenerated key (not another regen).
        val reloaded = PersistentOperatorDeviceKey(keyFile).loadOrGenerate()
        assertTrue(
            regenerated.public.encoded.contentEquals(reloaded.public.encoded),
            "the regenerated key must persist and reload stably across launches (idempotent)",
        )

        Files.deleteIfExists(keyFile)
        Files.deleteIfExists(dir)
    }
}
