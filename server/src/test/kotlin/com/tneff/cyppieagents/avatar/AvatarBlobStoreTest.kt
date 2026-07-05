package com.tneff.cyppieagents.avatar

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-215 — the avatar blob store teeth. Load-bearing: **path-traversal-safe** (a hostile agentId/projectId
 * can never escape the root), per-project isolation, cascade-delete, and the null-root off-switch. Hermetic
 * (a temp dir).
 */
class AvatarBlobStoreTest {

    private val png = byteArrayOf(1, 2, 3, 4, 5)

    @Test fun writeReadDelete_roundTrip() {
        val root = Files.createTempDirectory("avatarblob").toFile()
        val store = AvatarBlobStore(root)
        assertTrue(store.write("default", "backend", png))
        assertContentEquals(png, store.read("default", "backend"))
        assertTrue(store.delete("default", "backend"))
        assertNull(store.read("default", "backend"), "after delete the blob is gone")
    }

    @Test fun traversalKeys_areRejected_neverEscapeTheRoot() {
        // HERMETIC: an EXCLUSIVELY-ours sandbox is the store's PARENT, so the "did anything escape the root?"
        // scan inspects only what THIS test controls — never shared /tmp (which, if dirty with a pre-existing
        // /tmp/etc, false-REDs the scan; Test's CYP-215 finding). The sandbox starts empty; the only entry
        // under it must remain `root` itself.
        val sandbox = Files.createTempDirectory("avatarblob-trav").toFile()
        val root = java.io.File(sandbox, "avatars").apply { mkdirs() }
        val store = AvatarBlobStore(root)
        val hostile = listOf("../../etc/passwd", "..", ".", "a/b", "a\\b", "", "x/../../y")
        for (bad in hostile) {
            assertFalse(store.write("default", bad, png), "write must reject hostile agentId '$bad'")
            assertNull(store.read("default", bad), "read must reject hostile agentId '$bad'")
            assertFalse(store.delete("default", bad), "delete must reject hostile agentId '$bad'")
            assertFalse(store.write(bad, "backend", png), "write must reject hostile projectId '$bad'")
        }
        // Nothing escaped: the ONLY filesystem entry under our exclusive sandbox is `root` itself — no hostile
        // key created a file/dir anywhere (above, beside, or inside the root).
        val escaped = sandbox.walkTopDown().filter { it != sandbox && it != root }.toList()
        assertTrue(escaped.isEmpty(), "a hostile key created something outside the store root: ${escaped.joinToString { it.path }}")
    }

    @Test fun perProjectIsolation_andCascadeDelete() {
        val root = Files.createTempDirectory("avatarblob-iso").toFile()
        val store = AvatarBlobStore(root)
        store.write("pA", "a1", png); store.write("pA", "a2", png); store.write("pB", "b1", png)
        assertEquals(2, store.deleteByProject("pA"), "cascade purges exactly pA's blobs")
        assertNull(store.read("pA", "a1")); assertNull(store.read("pA", "a2"))
        assertContentEquals(png, store.read("pB", "b1"), "pB is untouched by pA's cascade")
    }

    @Test fun nullRoot_isAQuietNoOp() {
        val store = AvatarBlobStore(null)
        assertFalse(store.write("default", "backend", png))
        assertNull(store.read("default", "backend"))
        assertFalse(store.delete("default", "backend"))
        assertEquals(0, store.deleteByProject("default"))
    }
}
