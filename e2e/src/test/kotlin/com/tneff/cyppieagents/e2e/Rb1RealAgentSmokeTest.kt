package com.tneff.cyppieagents.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RB1 / CYP-110 harness smoke (Tier B). **RUN_RB1=1-gated**: when unset (the default gate) every test
 * early-returns and does nothing — it NEVER spawns a real `claude` and NEVER consumes quota. The single
 * quota-aware live run fires only when the operator sets RUN_RB1=1 with a logged-in subscription
 * (no `ANTHROPIC_API_KEY`), on the human/PO creds-go.
 *
 * This proves the [Rb1RealAgentHarness] wiring (pinned CLI, no-key OAuth fail-closed, real spawn + real
 * git against a throwaway sandbox). The full S8 journey (PO decomposes → worker commits/pushes to the
 * sandbox → status) is the Tester's to drive on this harness.
 */
class Rb1RealAgentSmokeTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    @Test
    fun rb1_realPlatformBootsAgainstSandbox_onOauthOnly() {
        if (!Rb1RealAgentHarness.rb1Enabled()) return // skipped in the default gate — no live run, no quota

        // Evidence: the runtime claude matches the pinned CLI, and no API key is present (OAuth only).
        val version = Rb1RealAgentHarness.assertPinnedClaudeCli()
        println("RB1: pinned claude OK → $version")
        Rb1RealAgentHarness.requireNoApiKeyInEnv()

        val gitRoot = Files.createTempDirectory("rb1-gitroot").toFile()
        val sandbox = Files.createTempDirectory("rb1-sandbox").toFile()
        val repoUrl = Rb1RealAgentHarness.initSandboxRepo(sandbox)
        println("RB1: sandbox repo at $repoUrl")

        val booted = Rb1RealAgentHarness.bootRealAgentPlatform(gitRoot, repoUrl, scope)
        try {
            // A real `claude` session spawned for the worker over the real mediation path (no key injected).
            assertTrue("backend" in booted.bootedAgents, "the worker booted a real claude session")
            assertNotNull(booted.connectorSessions.session("backend"))
            println("RB1: real platform booted; agents=${booted.bootedAgents}")
        } finally {
            booted.bootedAgents.forEach { booted.connectorSessions.remove(it) }
        }
    }
}
