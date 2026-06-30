package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reviewer #3 env isolation, tested with the REAL spawner spawning a real `printenv`: a host env
 * var that is NOT whitelisted (here OPERATOR_TOKEN) must not reach the child, while the injected
 * env (HUB_AGENT_ID) and the whitelisted PATH do.
 */
class ProcessBuilderSpawnerTest {

    @Test
    fun realSpawnerDoesNotLeakNonWhitelistedHostEnv() = runBlocking {
        val realPath = System.getenv("PATH") ?: "/usr/bin:/bin"
        // The "host env" contains a secret-shaped var that is NOT in the passthrough whitelist.
        val hostEnv = mapOf("PATH" to realPath, "OPERATOR_TOKEN" to "should-not-leak")
        val spawner = ProcessBuilderSpawner(envSource = { hostEnv[it] })
        val tmp = Files.createTempDirectory("env-iso").toFile()

        val process = spawner.spawn(listOf("printenv"), tmp, mapOf("HUB_AGENT_ID" to "backend"))
        val lines = process.stdoutLines.toList()
        process.destroy()

        assertTrue(lines.any { it == "HUB_AGENT_ID=backend" }, "injected env should pass through")
        assertTrue(lines.any { it.startsWith("PATH=") }, "whitelisted PATH should pass through")
        assertFalse(
            lines.any { it.startsWith("OPERATOR_TOKEN=") },
            "a non-whitelisted host secret must NOT reach the agent process",
        )
    }

    /**
     * CYP-168 regression: `USER`/`LOGNAME` MUST reach the spawned agent — on macOS the CLI's OAuth
     * credential lives in the Keychain keyed by the calling user, so without them every real turn
     * fails "Not logged in" (only the `ANTHROPIC_API_KEY` path worked; the RB1 file-cred harness
     * couldn't catch it). The hygiene invariant still holds: a non-whitelisted host var must NOT leak.
     * Mutation guard: drop `USER`/`LOGNAME` from [ProcessBuilderSpawner.passthroughEnv] → this reds.
     */
    @Test
    fun realSpawnerPassesUserAndLognameForOAuthButStillNotSecrets() = runBlocking {
        val realPath = System.getenv("PATH") ?: "/usr/bin:/bin"
        val hostEnv = mapOf(
            "PATH" to realPath,
            "USER" to "thorsten",
            "LOGNAME" to "thorsten",
            "OPERATOR_TOKEN" to "should-not-leak", // non-whitelisted → must stay out (hygiene)
        )
        val spawner = ProcessBuilderSpawner(envSource = { hostEnv[it] })
        val tmp = Files.createTempDirectory("env-user").toFile()

        val process = spawner.spawn(listOf("printenv"), tmp, mapOf("HUB_AGENT_ID" to "backend"))
        val lines = process.stdoutLines.toList()
        process.destroy()

        assertTrue(lines.any { it == "USER=thorsten" }, "USER must reach the agent (macOS Keychain OAuth, CYP-168)")
        assertTrue(lines.any { it == "LOGNAME=thorsten" }, "LOGNAME must reach the agent (macOS Keychain OAuth, CYP-168)")
        assertFalse(
            lines.any { it.startsWith("OPERATOR_TOKEN=") },
            "adding USER/LOGNAME must not widen the whitelist to non-whitelisted host secrets",
        )
    }
}
