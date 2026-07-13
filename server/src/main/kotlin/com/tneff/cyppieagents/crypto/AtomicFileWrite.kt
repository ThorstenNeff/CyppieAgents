package com.tneff.cyppieagents.crypto

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * CYP-525 (GE5/GE7 — H1/H1b) — a **crash-atomic** file write: `write temp → fsync(temp) → atomic rename() → fsync(dir)`.
 * A reader ever sees ONLY the prior file (or absent) OR the FULLY-new one — **never a torn/partial in-place write**.
 * This is the durable primitive under the combined finalize record {device anchor + code-hashes}: the anchor and the
 * hashes are ONE value swapped in by a single POSIX `rename`, so a power-loss crash can never leave a 2-key split nor a
 * torn/corrupt record (H1/H1b). ★ **fsync order (Reviewer):** the directory fsync comes **AFTER** the rename — it
 * persists the new dentry; without it the rename itself would not be durable across a power loss.
 *
 * A crash before the rename leaves the OLD target intact (the temp is orphaned, not the target). The [crashHook] seam
 * lets a test inject a failure at exactly that point (after fsync, before rename) to prove the old-state-preserved
 * property; production passes the default no-op.
 */
object AtomicFileWrite {
    fun write(target: File, bytes: ByteArray, crashHook: () -> Unit = {}) {
        val dir = target.absoluteFile.parentFile ?: File(".")
        dir.mkdirs()
        val temp = File.createTempFile(".${target.name}", ".tmp", dir)
        try {
            FileOutputStream(temp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync() // fsync(temp): the FULL contents are durable before we swap them in
            }
            crashHook() // test seam: a crash HERE (after fsync, before rename) must leave the OLD target intact
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) // the atomic commit point
            fsyncDir(dir) // fsync(dir) AFTER the rename → the new directory entry is durable
        } catch (e: Throwable) {
            runCatching { temp.delete() } // a failed write orphans the temp, never the target
            throw e
        }
    }

    /** fsync the directory so the rename (a dentry change) survives a power loss. Best-effort: some platforms/FS reject
     *  opening a directory as a channel — the file fsync + ATOMIC_MOVE are the primary guarantee; this is the extra. */
    private fun fsyncDir(dir: File) {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
