package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-183 / P4.1 — hermetic assertion that the shipped GitHub OIDC provider config keeps the client_secret a
 * **secret-safe env reference**, never a literal in the repo (the P2.1/`SmtpCourierConfigTest` pattern). The
 * client_id is PUBLIC and safe; the secret must come from `GITHUB_CLIENT_SECRET` at deploy time.
 *
 * **(E) teeth:** `client_secret` MUST be an env reference (`${...}`) — a literal reds this. Covered by the
 * existing `inputs.file` wiring (CYP-178 CC2), so editing ONLY the yml re-runs — no stale-green.
 *
 * NB: this asserts the CONFIG shape only. The load-bearing **linking-takeover** safety (S1) is Kratos v1.3.0
 * RUNTIME behavior — proven by the deploy-coordinated spike, not by this test.
 */
class OidcProviderConfigTest {

    @Test
    fun githubOidc_clientSecretIsEnvReference_neverALiteral() {
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        assertTrue(Regex("(?m)^\\s*oidc:\\s*$").containsMatchIn(text), "selfservice.methods.oidc block must be present")
        assertTrue(Regex("provider:\\s*github").containsMatchIn(text), "the github provider must be configured")
        assertTrue(Regex("client_id:\\s*Ov23lioeKkKuWAesQBKT").containsMatchIn(text), "the public GitHub client_id must be pinned")

        val secret = Regex("client_secret:\\s*(\\S+)").find(text)?.groupValues?.get(1)
            ?: fail("oidc github client_secret must be set")
        assertTrue(
            Regex("^\\$\\{.*}$").matches(secret),
            "github client_secret must be an env reference (\${...}), never a literal secret — was: $secret",
        )
        // The mapper file must ship alongside the config.
        assertTrue(repoFile("deploy/kratos/oidc.github.jsonnet").exists(), "the github OIDC mapper jsonnet must be shipped")
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { val f = File(dir, rel); if (f.exists()) return f; dir = dir.parentFile }
        fail("could not locate '$rel'")
    }
}
