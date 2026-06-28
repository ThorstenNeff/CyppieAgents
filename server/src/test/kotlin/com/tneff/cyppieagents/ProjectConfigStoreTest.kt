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
        store.setApiKey("alpha", "ka")
        store.setApiKey("beta", "kb")
        assertEquals("ka", store.resolvedApiKey("alpha"))
        assertEquals("kb", store.resolvedApiKey("beta"))
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
            val file = dir.resolve("project-config.json").toFile()
            ProjectConfigStore(file, RepoConfig("u", "main"), secrets()).apply {
                setApiKey("default", "persisted-key")
                setRepo("default", "https://x/y.git", "dev")
            }
            assertTrue(file.exists())

            // 0600 guardrail (POSIX): owner rw only. Mutation: drop restrictToOwner → wider perms → red.
            if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()),
                    "project-config.json must be 0600 (holds a secret at rest)",
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
