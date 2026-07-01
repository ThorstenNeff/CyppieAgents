package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-181 / P2.1 — hermetic assertion that the shipped Kratos courier config keeps SMTP a **secret-safe
 * env reference**, never a literal password in the repo. The SMTP `connection_uri` carries the relay
 * password; committing it would leak a credential into the shared remote (the D3 hygiene, and the P2
 * analogue of the RC2 anti-enumeration config-assert).
 *
 * **(E) teeth:** `connection_uri` MUST be an env reference (`${...}`) — a literal (`smtp://user:pw@host`)
 * reds this test. The `test` task already declares `deploy/kratos/kratos.reference.yml` as an `inputs.file`
 * (CYP-178 CC2), so editing ONLY the yml re-runs this — no stale-green.
 */
class SmtpCourierConfigTest {

    @Test
    fun courierSmtp_isEnvReference_neverALiteralSecret() {
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        // The courier SMTP block must exist (Kratos needs it to send recovery/verification/email-change mail).
        assertTrue(Regex("(?m)^courier:\\s*$").containsMatchIn(text), "courier block must be present")
        assertTrue(Regex("(?m)^\\s*smtp:\\s*$").containsMatchIn(text), "courier.smtp block must be present")
        assertTrue(Regex("from_address:\\s*\\S+").containsMatchIn(text), "courier.smtp.from_address must be set")

        val uri = Regex("connection_uri:\\s*(\\S+)").find(text)?.groupValues?.get(1)
            ?: fail("courier.smtp.connection_uri must be set")
        // The load-bearing teeth: it must be an env reference, not a literal SMTP URL with an inline password.
        assertTrue(
            Regex("^\\$\\{.*}$").matches(uri),
            "courier.smtp.connection_uri must be an env reference (\${...}), never a literal secret — was: $uri",
        )
        assertTrue(
            !uri.contains("://") && !uri.contains("@"),
            "courier.smtp.connection_uri must not contain an inline SMTP URL / credentials — was: $uri",
        )
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { val f = File(dir, rel); if (f.exists()) return f; dir = dir.parentFile }
        fail("could not locate '$rel' from ${System.getProperty("user.dir")}")
    }
}
