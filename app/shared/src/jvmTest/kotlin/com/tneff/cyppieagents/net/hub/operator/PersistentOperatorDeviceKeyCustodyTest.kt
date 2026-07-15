package com.tneff.cyppieagents.net.hub.operator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-583 — [PersistentOperatorDeviceKey.loadOrGenerate] fail-closed posture (supersedes CYP-580's WARN-log-only
 * interim; that regen behavior is now inverted). A PRESENT-but-corrupt custody file must fail closed as
 * [DeviceKeyCustody.Corrupt] — NEVER silently regenerate a new device identity (the `tamper → re-enroll` seizure
 * vector; the client mirror of the server's `rejectTampered` + the vault's `VaultOpen.Corrupt`). An ABSENT file still
 * first-runs a fresh key (the legit first-enroll — deliberately untouched, never over-block first-enroll).
 *
 * **Mutation (RED):** revert the present-corrupt branch to `generateAndPersist()` (the CYP-580 behavior) → the
 * `Corrupt` assertion reddens (it returns a regenerated `Ready` and overwrites the corrupt file instead).
 */
class PersistentOperatorDeviceKeyCustodyTest {

    @Test
    fun presentButCorruptFile_isDeviceCustodyCorrupt_neverSilentlyRegenerated() {
        val dir = Files.createTempDirectory("cyp583-devkey")
        val keyFile = dir.resolve("operator-device.key")
        Files.write(keyFile, byteArrayOf(1, 2, 3, 4, 5)) // present but unreadable (bad arity/format)

        val custody = PersistentOperatorDeviceKey(keyFile).loadOrGenerate()
        assertIs<DeviceKeyCustody.Corrupt>(
            custody,
            "a present-but-corrupt custody file must fail closed as Corrupt — NEVER a silently regenerated Ready",
        )
        // Non-bypassable: the corrupt file is NOT overwritten by a silent regenerate (its bytes are untouched).
        assertTrue(
            Files.readAllBytes(keyFile).contentEquals(byteArrayOf(1, 2, 3, 4, 5)),
            "a corrupt custody file must not be silently overwritten with a fresh identity",
        )

        Files.deleteIfExists(keyFile)
        Files.deleteIfExists(dir)
    }

    @Test
    fun absentFile_firstRunGeneratesReady_andPersistsStably() {
        // Legit first-enroll: an ABSENT file generates + persists a fresh key (UNTOUCHED by CYP-583).
        val dir = Files.createTempDirectory("cyp583-devkey-absent")
        val keyFile = dir.resolve("operator-device.key")

        val ready = assertIs<DeviceKeyCustody.Ready>(
            PersistentOperatorDeviceKey(keyFile).loadOrGenerate(),
            "an absent file first-runs a fresh Ready key",
        )
        // Persisted + stable: a fresh instance now LOADS the same key (Ready), not another regen.
        val reloaded = assertIs<DeviceKeyCustody.Ready>(
            PersistentOperatorDeviceKey(keyFile).loadOrGenerate(),
            "a present valid file loads Ready",
        )
        assertTrue(
            ready.keyPair.public.encoded.contentEquals(reloaded.keyPair.public.encoded),
            "the first-run key persists and reloads stably (idempotent)",
        )

        Files.deleteIfExists(keyFile)
        Files.deleteIfExists(dir)
    }
}
