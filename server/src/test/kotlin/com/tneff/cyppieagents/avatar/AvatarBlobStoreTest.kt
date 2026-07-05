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
        val root = Files.createTempDirectory("avatarblob-trav").toFile()
        val store = AvatarBlobStore(root)
        val hostile = listOf("../../etc/passwd", "..", ".", "a/b", "a\\b", "", "x/../../y")
        for (bad in hostile) {
            assertFalse(store.write("default", bad, png), "write must reject hostile agentId '$bad'")
            assertNull(store.read("default", bad), "read must reject hostile agentId '$bad'")
            assertFalse(store.delete("default", bad), "delete must reject hostile agentId '$bad'")
            assertFalse(store.write(bad, "backend", png), "write must reject hostile projectId '$bad'")
        }
        // Nothing escaped: the ONLY thing under the temp root is (at most) the project dir — never `etc`, `passwd`, …
        val leaked = root.parentFile.listFiles { f -> f.name == "etc" || f.name == "passwd" || f.name == "y" }
        assertTrue(leaked == null || leaked.isEmpty(), "a hostile key escaped the root: ${leaked?.joinToString { it.path }}")
        assertTrue(root.walkTopDown().none { it.name == "passwd" }, "a traversal wrote inside the tree")
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
