package com.tneff.cyppieagents.avatar

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * CYP-215 — the store for the **re-encoded** avatar PNGs (never the original upload), per (projectId, agentId).
 *
 * CYP-223 (CYP-220 Phase 1): **store-seam interface.** The default on-disk impl is [FileAvatarBlobStore]; a
 * future PG impl (`bytea`) implements this same interface, selected by the boot factory from the store binding.
 * The companion `invoke` keeps `AvatarBlobStore(root)` construction resolving to the file impl (the current
 * factory choice) — no behavior change.
 */
@com.tneff.cyppieagents.tier.StoreKey("avatar_blob")
interface AvatarBlobStore {
    /** Store the re-encoded PNG for an agent (overwrites any prior one). Returns false if disabled/invalid. */
    fun write(projectId: String, agentId: String, png: ByteArray): Boolean
    /** The stored re-encoded PNG bytes, or null if none / disabled / an unsafe key. */
    fun read(projectId: String, agentId: String): ByteArray?
    /** Delete one agent's avatar blob. Returns true if a file was removed. */
    fun delete(projectId: String, agentId: String): Boolean
    /** Cascade: purge a whole project's avatar directory. Returns the number of blobs removed. */
    fun deleteByProject(projectId: String): Int

    companion object {
        /** Factory seam (CYP-223): the current impl choice is the file store. */
        operator fun invoke(root: File?): AvatarBlobStore = FileAvatarBlobStore(root)
    }
}

/**
 * The on-disk avatar blob store. Bytes live out-of-repo at `<root>/<projectId>/<agentId>.png`
 * (root = `.cyppie/avatars`, gitignored, per-project) — atomic-move write.
 *
 * **Path-traversal-safe by construction:** the file name is derived ONLY from the validated `projectId` +
 * `agentId` (never a user-supplied filename). Both are re-validated here ([safeSegment]) as defence in
 * depth — a segment with a slash, `..`, or any char outside `[A-Za-z0-9._-]` is rejected before it can
 * touch the filesystem, so `../../etc/…` can never be formed. A `null` root disables persistence (tests /
 * a store-less boot) — every op is then a no-op / empty read.
 */
class FileAvatarBlobStore(private val root: File?) : AvatarBlobStore {

    private val lock = Any()

    override fun write(projectId: String, agentId: String, png: ByteArray): Boolean = synchronized(lock) {
        val target = fileFor(projectId, agentId) ?: return false
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(png)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }

    override fun read(projectId: String, agentId: String): ByteArray? = synchronized(lock) {
        val f = fileFor(projectId, agentId) ?: return null
        if (f.isFile) f.readBytes() else null
    }

    override fun delete(projectId: String, agentId: String): Boolean = synchronized(lock) {
        val f = fileFor(projectId, agentId) ?: return false
        f.isFile && f.delete()
    }

    override fun deleteByProject(projectId: String): Int = synchronized(lock) {
        val dir = dirFor(projectId) ?: return 0
        if (!dir.isDirectory) return 0
        val pngs = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray()
        val n = pngs.count { it.delete() }
        dir.delete() // best-effort remove the now-empty project dir
        n
    }

    private fun dirFor(projectId: String): File? {
        val r = root ?: return null
        val p = safeSegment(projectId) ?: return null
        return File(r, p)
    }

    private fun fileFor(projectId: String, agentId: String): File? {
        val dir = dirFor(projectId) ?: return null
        val a = safeSegment(agentId) ?: return null
        return File(dir, "$a.png")
    }

    /** Accept only a single safe path segment; reject anything that could escape the directory. */
    private fun safeSegment(s: String): String? =
        if (s.isNotEmpty() && s != "." && s != ".." && s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } && "/" !in s && "\\" !in s) s
        else null
}
