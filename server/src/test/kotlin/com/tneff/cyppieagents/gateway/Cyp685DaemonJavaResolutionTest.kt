package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-685 — pins that each macOS LaunchDaemon wrapper (hub/relay/gateway) resolves java via an ABSOLUTE,
 * _cyppie-readable JAVA_HOME, **fail-closed**, and NEVER a bare `java` (PATH-reliant) invocation.
 *
 * Root (cold-boot NO-GO, PL-0006): the CYP-670 wrappers ran a bare `java` (relying on PATH). But a system
 * LaunchDaemon's PATH is /usr/bin:/bin:/usr/sbin:/sbin (no JDK), and the corretto JDK lived under a console user's
 * ~/.gradle (unreadable by the dedicated `_cyppie` daemon user) → "Unable to locate a Java Runtime", every daemon
 * failed to boot. The same "the daemon can't see the console user's home" class as the F1 state + F2 creds. Fix:
 * JAVA_HOME (from the 0600 env file; deploy relocates corretto-21 → /opt/cyppie-hub/jdk, chown _cyppie) → exec
 * "$JAVA_HOME/bin/java", fail-closed if JAVA_HOME is unset or $JAVA_HOME/bin/java is not executable — never a PATH
 * fall-through.
 *
 * Mutation-proven: revert any wrapper's launch to a bare `java` invocation → assertion (1) reds.
 */
class Cyp685DaemonJavaResolutionTest {

    private val wrappers = listOf(
        "hub" to "deploy/launchd/hub-run.sh",
        "relay" to "deploy/launchd/relay-run.sh",
        "gateway" to "deploy/launchd/gateway-run.sh",
    )

    @Test
    fun eachWrapper_execsAbsoluteJavaHomeJava_failClosed_neverBareJava() {
        for ((name, rel) in wrappers) {
            val text = repoFile(rel).readText()
            // Strip comment lines so a comment that mentions java can neither false-match nor mask the code assertion.
            val code = text.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

            // (1) NEVER a bare `java` launch (PATH-reliant — the exact CYP-685 regression). The mutation the PO runs
            //     (revert to a bare `java` invocation) reds HERE.
            assertFalse(
                Regex("(?m)^\\s*exec\\s+java\\b").containsMatchIn(code),
                "[$name] wrapper must NOT launch bare `java` (the LaunchDaemon PATH has no JDK) — CYP-685; code:\n$code",
            )
            // (2) execs the ABSOLUTE, JAVA_HOME-based java.
            assertTrue(
                code.contains("\$JAVA_HOME/bin/java") && code.contains("exec \"\$JAVA_BIN\""),
                "[$name] wrapper must exec the absolute \"\$JAVA_BIN\" (= \$JAVA_HOME/bin/java) — CYP-685",
            )
            // (3) fail-closed: JAVA_HOME is REQUIRED and $JAVA_HOME/bin/java is verified executable (no PATH fall-through).
            assertTrue(
                code.contains("JAVA_HOME:?"),
                "[$name] wrapper must REQUIRE JAVA_HOME (fail-closed \${JAVA_HOME:?…}) — CYP-685",
            )
            assertTrue(
                code.contains("[ -x \"\$JAVA_BIN\" ]"),
                "[$name] wrapper must verify \$JAVA_BIN is executable (fail-closed, no bare-java fall-through) — CYP-685",
            )
        }
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        fail("could not locate '$rel' from ${System.getProperty("user.dir")}")
    }
}
