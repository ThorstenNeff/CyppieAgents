package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.RemoteTokenStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CYP-171 / SEC7 — the remote-token store is secret-at-rest: reload-survival, 0600 file / 0700 dir, and a
 *  corrupt file fails closed (starts empty, never bricks boot, contents never logged/backed-up). */
class RemoteTokenStoreTest {

    private fun tempTarget(): File {
        val dir = Files.createTempDirectory("rts").toFile()
        return File(File(dir, "data"), "remote-tokens.json") // a parent the store must create + lock down
    }

    @Test
    fun bindingSurvivesAFreshStoreFromTheSameFile() {
        val file = tempTarget()
        RemoteTokenStore(file).put("backend", "tok-be")

        val reloaded = RemoteTokenStore(file) // simulate the next boot
        assertEquals(mapOf("backend" to "tok-be"), reloaded.all(), "the minted token survives a restart")
    }

    @Test
    fun fileAndDirAreOwnerOnly() {
        val file = tempTarget()
        RemoteTokenStore(file).put("backend", "tok-be")

        val perms = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
        if (perms != null) { // POSIX host (the CI/dev box is Linux)
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, "token file is 0600")
            val dirPerms = Files.getPosixFilePermissions(file.parentFile.toPath())
            assertTrue(
                dirPerms == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                "token dir is 0700 (a local non-owner cannot list it): $dirPerms",
            )
        }
    }

    @Test
    fun corruptFileStartsEmpty_doesNotThrow() {
        val file = tempTarget()
        file.parentFile.mkdirs()
        file.writeText("{ not valid json ][")

        val store = RemoteTokenStore(file) // must NOT throw
        assertTrue(store.all().isEmpty(), "a corrupt store starts empty (fail-closed, no brick)")
    }

    @Test
    fun removeDropsTheEntry() {
        val file = tempTarget()
        val store = RemoteTokenStore(file)
        store.put("a", "tok-a"); store.put("b", "tok-b")
        store.remove("a")
        assertEquals(mapOf("b" to "tok-b"), RemoteTokenStore(file).all(), "removal is persisted")
    }
}
