package com.tneff.cyppieagents.crypto

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-525 (H1/H1b) — [AtomicFileWrite] is the crash-atomic durable primitive under the combined finalize record. A
 * write is all-or-nothing (temp → fsync → atomic rename → dir fsync): a reader sees the prior/absent file or the
 * fully-new one, never a torn/partial in-place write, and a crash before the rename leaves the OLD target intact.
 */
class AtomicFileWriteTest {

    private fun tmpDir(): File = Files.createTempDirectory("cyp525-atomic").toFile()

    @Test
    fun writesAndRereads_roundTrip() {
        val target = File(tmpDir(), "record.bin")
        val bytes = "combined {anchor + code-hashes}".encodeToByteArray()
        AtomicFileWrite.write(target, bytes)
        assertTrue(target.readBytes().contentEquals(bytes), "the written record reads back byte-identical")
    }

    @Test
    fun replace_leavesNoTempLeak() {
        val dir = tmpDir()
        val target = File(dir, "record.bin")
        AtomicFileWrite.write(target, "A".encodeToByteArray())
        AtomicFileWrite.write(target, "B".encodeToByteArray())
        assertEquals("B", target.readText(), "a second write replaces the record")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no orphaned .tmp files after a successful write")
    }

    @Test
    fun crashBeforeRename_leavesOldTargetIntact_notTorn() {
        val target = File(tmpDir(), "record.bin")
        AtomicFileWrite.write(target, "A".encodeToByteArray())
        assertEquals("A", target.readText())
        // Simulate a power-loss crash AFTER the temp is fsync'd but BEFORE the atomic rename. The target must still be
        // the OLD "A" — never a torn/partial "B" (the rename is the sole commit point). MUT (in-place write, no
        // temp+rename) → the crash would leave a torn/partial record = RED.
        assertFailsWith<RuntimeException> {
            AtomicFileWrite.write(target, "B-a-much-longer-value-that-would-tear".encodeToByteArray()) {
                throw RuntimeException("power loss after fsync, before rename")
            }
        }
        assertEquals("A", target.readText(), "a crash before the atomic rename leaves the OLD content — never torn/partial")
    }
}
