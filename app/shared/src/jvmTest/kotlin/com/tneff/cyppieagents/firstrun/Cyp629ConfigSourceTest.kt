package com.tneff.cyppieagents.firstrun

import com.tneff.cyppieagents.settings.ApiKeyState
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.RepoConfigState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-629 live-wiring (B1) — [ConfigRepositoryFirstRunConfigSource] derives the gate status from the REAL config REST
 * (apikey + repo), and — crucially — NEVER fabricates a clone success. There is no backend clone-status field, so a
 * configured hub derives to `CONFIGURED_NEVER_CLONED`, never `CLONED_OK`; combined with the gate's TRANSPARENT-on-
 * configured rule the wizard still completes, without the client ever claiming "cloned".
 */
class Cyp629ConfigSourceTest {

    private class FakeConfig(private val apiKey: ApiKeyState, private val repo: RepoConfigState) : ConfigRepository {
        override suspend fun getRepo(): RepoConfigState = repo
        override suspend fun putRepo(url: String, branch: String) = RepoConfigState.Configured(url, branch)
        override suspend fun getApiKey(): ApiKeyState = apiKey
        override suspend fun putApiKey(apiKey: String) = ApiKeyState(set = true, masked = "***1234")
    }

    @Test
    fun unconfiguredHub_derivesNotConfigured() = runTest {
        val s = ConfigRepositoryFirstRunConfigSource(
            FakeConfig(ApiKeyState(set = false, masked = null), RepoConfigState.NotConfigured),
        ).status()
        assertTrue(s.loaded)
        assertEquals(false, s.apiKeySet)
        assertEquals(CloneStatus.NOT_CONFIGURED, s.cloneStatus)
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(s))
    }

    @Test
    fun keySet_repoUnset_landsOnRepo() = runTest {
        val s = ConfigRepositoryFirstRunConfigSource(
            FakeConfig(ApiKeyState(set = true, masked = "***1234"), RepoConfigState.NotConfigured),
        ).status()
        assertTrue(s.apiKeySet)
        assertEquals(CloneStatus.NOT_CONFIGURED, s.cloneStatus)
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(s))
        assertEquals(FirstRunStep.REPO, firstRunOpenStep(s))
    }

    @Test
    fun configuredHub_isTransparent_andNeverFabricatesClonedOk() = runTest {
        // ★ B1 STRUCTURAL invariant: a fully configured hub derives to CONFIGURED_NEVER_CLONED — NEVER CLONED_OK (there
        // is no backend clone source). Mutation: decode `raw = CloneStatus.CLONED_OK` in the source → this reddens
        // (the client would fabricate clone success). Yet the gate still reaches TRANSPARENT (configured), so the
        // wizard completes — honesty AND completability, without a faked "cloned".
        val s = ConfigRepositoryFirstRunConfigSource(
            FakeConfig(ApiKeyState(set = true, masked = "***1234"), RepoConfigState.Configured("git@host:org/p.git", "main")),
        ).status()
        assertTrue(s.apiKeySet)
        assertNotEquals(CloneStatus.CLONED_OK, s.cloneStatus, "the client NEVER fabricates CLONED_OK — no backend clone source (B1)")
        assertEquals(CloneStatus.CONFIGURED_NEVER_CLONED, s.cloneStatus)
        assertEquals(FirstRunGateMode.TRANSPARENT, firstRunGateMode(s), "configured (key + repo set) → transparent, wizard completes")
    }
}
