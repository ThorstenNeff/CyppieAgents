package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-project config store (S15 / CYP-96): resolution (operator override → boot/env fallback), masked
 * views, persistence round-trip, and the at-rest **0600** guardrail. No cross-project read.
 */
class ProjectConfigStoreTest {

    private fun secrets(apiKey: String? = null) = Secrets(mapOf("t" to "po"), "op", apiKey = apiKey)

    @Test
    fun apiKeyOverrideWinsOverEnvFallback() {
        val store = ProjectConfigStore(null, RepoConfig("u", "main"), secrets(apiKey = "env-key"))
        assertEquals("env-key", store.resolvedApiKey("default")) // fallback to env
        store.setApiKey("default", "override-key")
        assertEquals("override-key", store.resolvedApiKey("default")) // operator override wins
    }

    @Test
    fun perProjectIsolation_noCrossProjectRead() {
        val store = ProjectConfigStore(null, RepoConfig("u", "main"), secrets())
        store.setApiKey("alpha", "key-alpha-1234") // >= MIN_SECRET_LEN (CYP-104)
        store.setApiKey("beta", "key-beta-5678")
        assertEquals("key-alpha-1234", store.resolvedApiKey("alpha"))
        assertEquals("key-beta-5678", store.resolvedApiKey("beta"))
        assertNull(store.resolvedApiKey("gamma")) // no override, no env key → null (fail-closed)
    }

    @Test
    fun apiKeyView_isMaskedOnly_neverPlaintext() {
        val store = ProjectConfigStore(null, RepoConfig("u", "main"), secrets())
        assertEquals(false, store.apiKeyView("default").set) // unset
        store.setApiKey("default", "sk-ant-superlongsecret-ABCD")
        val v = store.apiKeyView("default")
        assertTrue(v.set)
        assertEquals("***ABCD", v.masked)
        assertFalse(v.toString().contains("superlongsecret"))
    }

    @Test
    fun repoOverrideWinsOverBootFallback() {
        val store = ProjectConfigStore(null, RepoConfig("boot-url", "main"), secrets())
        assertEquals("boot-url", store.resolvedRepo("default").url) // fallback
        store.setRepo("default", "https://github.com/o/r.git", "dev")
        assertEquals(RepoConfig("https://github.com/o/r.git", "dev"), store.resolvedRepo("default"))
        assertEquals(true, store.repoView("default").configured)
    }

    @Test
    fun persistenceRoundTrip_andFileIs0600() {
        val dir = Files.createTempDirectory("pcs")
        try {
            // A NOT-yet-existing sub-dir, so the store's own mkdirs creates it (umask-default, world/group
            // traversable) and restrictDirToOwner must tighten it to 0700 — otherwise the dir assert can't
            // bite (Files.createTempDirectory already makes its dir 0700).
            val file = dir.resolve("gitroot/project-config.json").toFile()
            ProjectConfigStore(file, RepoConfig("u", "main"), secrets()).apply {
                setApiKey("default", "persisted-key")
                setRepo("default", "https://x/y.git", "dev")
            }
            assertTrue(file.exists())

            // 0600 file + 0700 dir guardrails (POSIX). Mutations: drop restrictToOwner → file wider → red;
            // drop restrictDirToOwner → dir 0775 → red. The secret never sits in a world-readable place.
            if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()),
                    "project-config.json must be 0600 (holds a secret at rest)",
                )
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(file.parentFile.toPath()),
                    "the secret's directory must be 0700 (not world/group traversable)",
                )
            }

            // A fresh store loads the persisted overrides.
            val reopened = ProjectConfigStore(file, RepoConfig("u", "main"), secrets())
            assertEquals("persisted-key", reopened.resolvedApiKey("default"))
            assertEquals("https://x/y.git", reopened.resolvedRepo("default").url)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
