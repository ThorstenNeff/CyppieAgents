package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-811 — the LOAD-BEARING replay vector is the operator-auth cookie `ory_kratos_session` (Kratos-set), NOT our
 * CSRF cookie. The per-hub distinct-loopback-IP invariant ([com.tneff.cyppieagents.boot.LoopbackHubLock]) isolates it
 * only if it is HOST-ONLY (no `Domain=`).
 *
 * **Primary guarantee (config-independent):** for an IP host (`127.0.0.1` / `127.0.0.2` — exactly what the invariant
 * uses) a `Domain=` attribute is INVALID per RFC 6265 §5.2.3 and ignored by the browser → the cookie is host-only by
 * construction → distinct loopback IPs are separate cookie jars. **Defense-in-depth (this tooth):** the Kratos
 * reference config also sets NO `domain:` on the session cookie (it would be moot for IPs, but a domain-host deploy
 * must not silently broaden it either). A `domain:` key added anywhere under a `cookie:` block → RED.
 * (Verified 2026-07-23: `kratos.reference.yml` session.cookie = name/same_site/secure/persistent, no domain; the
 * gateway relays Set-Cookie as-is with no Domain injection.)
 */
class Cyp811KratosCookieHostOnlyTest {

    @Test
    fun kratosSessionCookie_hasNoDomain_hostOnly() {
        val yml = repoRoot().resolve("deploy/kratos/kratos.reference.yml")
        assertTrue(yml.exists(), "missing ${yml.path}")
        // Strip comments, then assert no YAML `domain:` KEY (an operator/session cookie must never carry Domain=).
        val offenders = yml.readLines()
            .map { it.substringBefore('#') }
            .filter { Regex("""^\s*domain\s*:""").containsMatchIn(it) }
        assertTrue(
            offenders.isEmpty(),
            "kratos.reference.yml sets a cookie `domain:` (${offenders.map { it.trim() }}) — the operator session cookie " +
                "MUST stay host-only so the per-hub distinct-loopback-IP invariant isolates the real replay vector (§9.5/9.6).",
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }
}
