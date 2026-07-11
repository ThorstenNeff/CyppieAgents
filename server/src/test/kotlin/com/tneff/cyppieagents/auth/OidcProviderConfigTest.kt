package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-183 / P4.1 (+ env-secret fix) — hermetic assertion that the shipped GitHub OIDC provider config keeps
 * the client_secret OUT of the repo. The client_id is PUBLIC and safe. ⚠️ Kratos does NOT interpolate `${VAR}`
 * in YAML (a `${...}` ships as a literal → GitHub `incorrect_client_credentials`), so the secret is NOT a key
 * here at all — the whole providers array is injected in prod via the `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS`
 * env-override (secret box-local, never in file/log). Omitting it here also fails CLOSED if the env is missing.
 *
 * **(E) teeth:** no active `client_secret` key (no plaintext, no `${}`); the whole-config no-`${` teeth lives in
 * `SmtpCourierConfigTest`. BOTH runtime-read files are declared `inputs.file` in `server/build.gradle.kts` — a
 * `kratos.reference.yml` edit (CYP-178 CC2) OR an `oidc.github.jsonnet` edit (CYP-366) re-runs this test instead
 * of leaving it stale-green (the jsonnet was undeclared until CYP-366).
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

        // client_secret must NOT be an ACTIVE key in the YAML — neither a plaintext secret nor a ${VAR}
        // (Kratos doesn't interpolate → a ${} would ship as a literal). It is injected in prod via the
        // SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS env-override. A `# client_secret:` COMMENT is fine.
        assertTrue(
            !Regex("(?m)^\\s*client_secret:").containsMatchIn(text),
            "client_secret must NOT be an active YAML key (Kratos doesn't interpolate ${'$'}{}; inject the providers array via SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS env)",
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
