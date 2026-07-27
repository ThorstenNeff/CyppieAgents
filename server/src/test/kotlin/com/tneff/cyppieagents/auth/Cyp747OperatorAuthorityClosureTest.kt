package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-747 S-AAL2a-i — the browser-AAL2 CHOKEPOINT CLOSURE guard (design §9.1–9.3 + §11 Zahn 8a). TEST-ONLY: this
 * changes no behavior; it LOCKS the operator-authority surface so the later AAL2 gate (a-ii, at `resolvePrincipal`)
 * is a PROPERTY over the SOLE cookie→OPERATOR path, not a whack-a-mole over an enumerable list that a 21st edge
 * silently escapes. A build-time source-tree meta-test (mirrors the `repoFile` walk-up of `GatewayLaunchHardeningTest`
 * / `Rc2ConfigAssertionTest`).
 *
 * THREE structural legs, each pinned + POSITIVE-CONTROLLED (a broken detector must go RED, never silently green):
 *  ① operator-authority TYPE mint sites == a pinned FILE registry (a NEW mint → found≠pinned → RED). Two type
 *     families: `AuthPrincipal.{MachineOperator, Human(→OPERATOR, role COMPUTED not literal)}` + `TerminalPrincipal.Operator`.
 *  ② `isOperator(...)` call-sites pinned (a Boolean — constructs NO type, so ①-blind; completeness hygiene).
 *  ③ the SINGLE cookie-consumer confinement: (b) the identity-resolution call `idp.resolve(` and (a) the raw session
 *     credential strings live ONLY under `auth/`, never `routing/` — a `routing/` surface that resolves a session
 *     directly would bypass the a-ii AAL2 gate that sits INSIDE `resolvePrincipal`.
 *
 * If a legitimately-new site appears, the fix is: add the site to a-ii's AAL2 gate (or justify it) AND update the
 * pinned registry here — never just silence the test.
 */
class Cyp747OperatorAuthorityClosureTest {

    private val srcRoot = repoFile("server/src/main/kotlin")

    private data class Src(val rel: String, val code: String)

    /** Every server main `.kt`, with comments (block + line) stripped — so a mention in a KDoc/`//` never false-flags. */
    private fun sources(): List<Src> = srcRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { f ->
            val noBlock = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            val noLine = noBlock.lines().joinToString("\n") { it.substringBefore("//") }
            Src(f.relativeTo(srcRoot).path.replace('\\', '/'), noLine)
        }
        .toList()

    private fun filesMatching(pred: (String) -> Boolean): Set<String> =
        sources().filter { pred(it.code) }.map { it.rel }.toSet()

    // ─────────────────────────────── ① type-mint closure ───────────────────────────────

    private val HUMAN_CTOR = Regex("""\bHuman\(""")
    private val HUMAN_DECL = Regex("""class\s+Human\(""")           // the data-class DECLARATION, not a mint
    private val MACHINE_OP_MINT = Regex("""(return|->)\s*AuthPrincipal\.MachineOperator""")
    private val TERMINAL_OP_MINT = Regex("""(return|->)\s*TerminalPrincipal\.Operator""")

    private fun mintsOperatorAuthority(code: String): Boolean =
        (HUMAN_CTOR.containsMatchIn(code) && !HUMAN_DECL.containsMatchIn(code)) ||
            MACHINE_OP_MINT.containsMatchIn(code) ||
            TERMINAL_OP_MINT.containsMatchIn(code)

    /** The pinned set of files that PRODUCE an operator-authority principal. Grows ONLY by deliberate review. */
    private val OPERATOR_AUTHORITY_MINT_FILES = setOf(
        "com/tneff/cyppieagents/auth/Principal.kt",          // MachineOperator (token axis) + Human(computed role) (cookie axis)
        "com/tneff/cyppieagents/auth/CpJwtVerifier.kt",      // Human(sub, OPERATOR) — tunnel axis (device-PoP, NOT AAL2)
        "com/tneff/cyppieagents/routing/TerminalAccess.kt",  // TerminalPrincipal.Operator (token + verified-OPERATOR-session)
    )

    @Test
    fun operatorAuthorityMintSites_areClosedOverThePinnedRegistry() {
        val found = filesMatching(::mintsOperatorAuthority)
        // POSITIVE CONTROL: the detector must actually flag the known mint files (a broken regex → empty → caught here).
        for (f in OPERATOR_AUTHORITY_MINT_FILES) {
            assertTrue(f in found, "positive control FAILED: the operator-authority mint detector did not flag known mint site [$f] — detector is broken (would be silently green)")
        }
        // CLOSURE: no UNPINNED file may mint operator authority. A new WS/SSE/MCP wrapper that mints an operator type
        // → found has an extra file → RED → forces adding the AAL2 gate for it + pinning here.
        assertEquals(
            OPERATOR_AUTHORITY_MINT_FILES, found,
            "operator-authority mint sites drifted from the pinned registry. NEW mint sites (add a-ii AAL2 coverage + pin): " +
                "${found - OPERATOR_AUTHORITY_MINT_FILES}; STALE pins (removed a mint? un-pin): ${OPERATOR_AUTHORITY_MINT_FILES - found}",
        )
    }

    // ─────────────────────────────── ② isOperator call-site pin ───────────────────────────────

    private val IS_OPERATOR_CALL = Regex("""\bisOperator\(""")
    private val IS_OPERATOR_DECL = Regex("""fun\s+isOperator\(""")

    private val IS_OPERATOR_CALL_FILES = setOf(
        // ★ CYP-828 (deliberate review): the god-token->OPERATOR grants relocated from Principal/TerminalAccess/
        // AgentSocket to TokenRegistry.operatorEligible (= isOperator ∧ loopbackPosture). So the ONLY isOperator(...)
        // CALL now lives in operatorEligible's definition (Auth.kt). The reject-guard uses ::isOperator (a ref, not a
        // call) + the mint uses `== operatorToken`; neither matches IS_OPERATOR_CALL. Every grant seam routes
        // operatorEligible now (Cyp828OperatorEligibleTest pins that + the two-site confinement).
        "com/tneff/cyppieagents/routing/Auth.kt",
    )

    @Test
    fun isOperatorCallSites_areClosedOverThePinnedRegistry() {
        val found = filesMatching { code -> IS_OPERATOR_CALL.containsMatchIn(code.replace(IS_OPERATOR_DECL, " ")) }
        for (f in IS_OPERATOR_CALL_FILES) {
            assertTrue(f in found, "positive control FAILED: isOperator-call detector did not flag known call site [$f]")
        }
        assertEquals(
            IS_OPERATOR_CALL_FILES, found,
            "isOperator(...) call-sites drifted. NEW (pin + ensure it constructs no unguarded operator type): " +
                "${found - IS_OPERATOR_CALL_FILES}; STALE: ${IS_OPERATOR_CALL_FILES - found}",
        )
    }

    // ─────────────────────────────── ③ single cookie-consumer confinement ───────────────────────────────

    private fun isAuth(rel: String) = rel.startsWith("com/tneff/cyppieagents/auth/")
    private fun isRouting(rel: String) = rel.startsWith("com/tneff/cyppieagents/routing/")

    @Test
    fun identityResolution_isConfinedToAuth_neverRouting() {
        // ③(b) — the CLEAN half: the session→identity resolution call `idp.resolve(`. A `routing/` surface calling it
        // directly resolves an operator FROM the session WITHOUT the AAL2 gate that lives inside resolvePrincipal.
        val idpResolve = Regex("""idp\.resolve\(""")
        val consumers = filesMatching { idpResolve.containsMatchIn(it) }
        // POSITIVE CONTROL: the known auth/ consumer must be flagged.
        assertTrue(
            "com/tneff/cyppieagents/auth/Principal.kt" in consumers,
            "positive control FAILED: idp.resolve detector did not flag auth/Principal.kt — detector broken",
        )
        val leaked = consumers.filterNot { isAuth(it) }
        assertTrue(leaked.isEmpty(), "identity resolution (idp.resolve) leaked OUTSIDE auth/: $leaked (a routing/ caller bypasses the AAL2 chokepoint)")
        assertTrue(consumers.none { isRouting(it) }, "identity resolution appears in routing/: ${consumers.filter(::isRouting)}")
    }

    @Test
    fun rawSessionCredentialStrings_areConfinedToAuth() {
        // ③(a) — the coarser half: the raw session-credential names. EXCLUDES two provably-NON-consuming uses (they never
        // resolve identity): `logging/` (TokenRedactor — redacts them) and `contract/` (ContractGenerator — declares the
        // OpenAPI security scheme). Comments are already stripped, so routing/ KDoc mentions don't count.
        val rawCred = Regex("""ory_kratos_session|X-Session-Token""")
        val nonConsumer = { rel: String -> rel.startsWith("com/tneff/cyppieagents/logging/") || rel.startsWith("com/tneff/cyppieagents/contract/") }
        val refs = filesMatching { rawCred.containsMatchIn(it) }.filterNot(nonConsumer).toSet()
        // POSITIVE CONTROL: the known auth/ raw-cred consumers must be flagged.
        assertTrue(
            refs.any { it.contains("/auth/") },
            "positive control FAILED: raw-session-credential detector flagged nothing in auth/ — detector broken",
        )
        val leaked = refs.filterNot(::isAuth)
        assertTrue(
            leaked.isEmpty(),
            "raw session-credential strings (ory_kratos_session / X-Session-Token) consumed OUTSIDE auth/ (excl. logging redaction + contract decl): $leaked",
        )
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
