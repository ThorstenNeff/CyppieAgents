package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-181 / P2.1 + CYP-183 (env-secret fix) — hermetic assertions that no **secret** and no broken
 * **`${VAR}` interpolation placeholder** ships in the Kratos config.
 *
 * ⚠️ **Kratos does NOT interpolate `${VAR}` in YAML** — a `${...}` is sent verbatim as a literal string
 * (the real-path bug: `${GITHUB_CLIENT_SECRET}` reached GitHub as-is → incorrect_client_credentials, and
 * `${SMTP_CONNECTION_URI}` would break the SMTP stack in prod). Secrets are injected via Kratos **env-OVERRIDE
 * paths** (`COURIER_SMTP_CONNECTION_URI`, `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS`), never `${}` in the file.
 * The `test` task declares this yml as an `inputs.file` (CYP-178 CC2), so a yml-only edit re-runs — no stale-green.
 */
class SmtpCourierConfigTest {

    @Test
    fun courierSmtp_isCredentialFree_notAnEnvPlaceholder() {
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        assertTrue(Regex("(?m)^courier:\\s*$").containsMatchIn(text), "courier block must be present")
        assertTrue(Regex("(?m)^\\s*smtp:\\s*$").containsMatchIn(text), "courier.smtp block must be present")
        assertTrue(Regex("from_address:\\s*\\S+").containsMatchIn(text), "courier.smtp.from_address must be set")

        val uri = Regex("connection_uri:\\s*(\\S+)").find(text)?.groupValues?.get(1)
            ?: fail("courier.smtp.connection_uri must be set")
        // NOT a ${VAR} placeholder (Kratos would send it literally → broken; prod injects via COURIER_SMTP_CONNECTION_URI).
        assertTrue(!uri.startsWith("\${"), "connection_uri must NOT be a \${VAR} placeholder (Kratos does not interpolate) — was: $uri")
        // No inline credentials in the committed file — a real relay URI carries a password (`user:pw@host`); prod uses env.
        assertTrue(!uri.contains("@"), "connection_uri in the repo must be credential-free (no user:pw@host) — was: $uri")
    }

    /**
     * The load-bearing teeth (PO ask): the WHOLE Kratos config must contain **no `${` interpolation
     * placeholder** anywhere — since Kratos never interpolates them, any `${}` is a latent secret/config bug.
     * Reintroducing a `${SMTP_CONNECTION_URI}` / `${GITHUB_CLIENT_SECRET}` reds this.
     */
    @Test
    fun kratosConfig_hasNoDollarBraceInterpolation() {
        // Check only ACTIVE config (drop full-line + inline `#` comments — comments legitimately MENTION
        // ${VAR} to explain the footgun; a ${} in an active value is the bug).
        val active = repoFile("deploy/kratos/kratos.reference.yml").readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n") { it.substringBefore(" #") }
        val offenders = Regex("\\$\\{[^}]*}").findAll(active).map { it.value }.toList()
        assertTrue(
            offenders.isEmpty(),
            "Kratos does not interpolate \${VAR} — these active placeholders would ship as literals; inject via env-override paths instead: $offenders",
        )
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { val f = File(dir, rel); if (f.exists()) return f; dir = dir.parentFile }
        fail("could not locate '$rel' from ${System.getProperty("user.dir")}")
    }
}
