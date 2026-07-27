package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.participantFor
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-828 (god-token invariant v3.3) — the god-token→OPERATOR grant is loopback-gated at the SHARED source
 * ([TokenRegistry.operatorEligible] = raw identity ∧ [TokenRegistry] `loopbackPosture`), routed by all 5 grant seams.
 * Off-loopback (the fail-closed default) every seam DENIES; on-loopback it still GRANTS (the positive control — the
 * fail-closed rotation must NOT vacuously break operator). Raw [TokenRegistry.isOperator] stays a host-independent
 * IDENTITY for the reject-guard + mint only. Both the behaviour (deny AND grant) and the two closure layers are pinned.
 */
class Cyp828OperatorEligibleTest {

    private val OP = "tok-op"
    private fun registry(posture: Boolean) = TokenRegistry(emptyMap(), operatorToken = OP, loopbackPosture = posture)

    private val nullIdp = object : IdentityProvider {
        override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? = null
    }
    private class FakeRoles(private val op: Boolean) : RoleStore {
        override suspend fun roleOf(identityId: String): AuthRole = AuthRole.MEMBER
        override suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole = AuthRole.MEMBER
        override suspend fun list(): List<RoleAssignment> = emptyList()
        override suspend fun hasOperator(): Boolean = op
    }
    private fun deps(posture: Boolean, killSwitch: Boolean = false, hasOp: Boolean = false) =
        AuthDeps(registry(posture), nullIdp, FakeRoles(hasOp), { 1L }, operatorTokenDisabled = killSwitch)

    // ---- core: operatorEligible posture-gates; isOperator stays raw identity ----
    @Test
    fun operatorEligible_gatesOnPosture_isOperatorStaysIdentity() {
        assertTrue(registry(true).operatorEligible(OP), "★ POSITIVE control: on-loopback → operator STILL grants (not vacuous)")
        assertFalse(registry(false).operatorEligible(OP), "off-loopback → deny (fail-closed default)")
        // raw identity is host-independent (the reject-guard/mint use it; a compromised off-loopback host still
        // IDENTIFIES the static token to reject it on tunnel ports).
        assertTrue(registry(false).isOperator(OP))
        assertTrue(registry(true).isOperator(OP))
        assertFalse(registry(true).operatorEligible("not-the-token"))
    }

    // ---- seam #4: participantFor → OPERATOR_ID (the /ws/events + /api/events message-body egress, CYP-432) ----
    @Test
    fun participantFor_operatorId_onLoopbackOnly() {
        assertEquals(HubState.OPERATOR_ID, registry(true).participantFor(OP), "on-loopback → OPERATOR_ID (grant)")
        assertNull(registry(false).participantFor(OP), "off-loopback → null: no OPERATOR_ID → no cross-agent event-body egress")
    }

    // ---- seam #1: resolvePrincipal → MachineOperator ----
    @Test
    fun resolvePrincipal_godToken_operatorOnLoopbackOnly() = runBlocking {
        assertEquals(AuthPrincipal.MachineOperator, resolvePrincipal(Credential(OP, null), deps(true)), "on-loopback → OPERATOR (grant)")
        assertNull(resolvePrincipal(Credential(OP, null), deps(false)), "off-loopback → null (401 deny)")
    }

    // ---- :132 T1d — the MEMBER-downgrade, BOTH directions (so neither absorbs the other, L#24) ----
    @Test
    fun principal132_memberDowngrade_onLoopbackOnly_neverLockOutPreserved() = runBlocking {
        // (b) ON loopback + kill-switch (operatorTokenDisabled ∧ hasOperator): :125 skips → :132 → MEMBER (never-lock-out
        // bootstrap PRESERVED — the legitimate downgrade the fix must keep).
        assertEquals(
            AuthPrincipal.MachineAgent(null),
            resolvePrincipal(Credential(OP, null), deps(true, killSwitch = true, hasOp = true)),
            "on-loopback kill-switch → MEMBER downgrade preserved (never-lock-out)",
        )
        // (a) OFF loopback: operatorEligible false at :132 too → falls through → null (401), NOT MEMBER (the V3-1 leak:
        // off-loopback god-token would have read cross-agent /api/events metadata as MEMBER).
        assertNull(
            resolvePrincipal(Credential(OP, null), deps(false, killSwitch = true, hasOp = true)),
            "off-loopback → 401, NOT MEMBER (V3-1 /api/events metadata leak closed)",
        )
    }

    // ---- Layer-A closure (source-scan): raw isOperator confined to {operatorEligible-def, reject-guard} ----
    @Test
    fun layerA_rawIsOperator_confinedToEligibilityDefAndRejectGuard() {
        // Every USE of `isOperator` (call `isOperator(` OR method-ref `::isOperator`), excluding the declaration, must
        // live ONLY in Auth.kt (the operatorEligible definition) + PlatformWiring.kt (the reject-guard). A grant seam
        // reverting to raw isOperator (MUT-A) or a 6th raw grant (MUT-B) makes a 3rd file appear → this reds.
        val use = Regex("""\bisOperator\(|::isOperator\b""")
        val files = serverMain().filter { f ->
            f.readLines().any { l ->
                val t = l.trimStart()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@any false // skip comment lines
                val code = l.substringBefore("//")
                use.containsMatchIn(code) && !code.contains("fun isOperator")
            }
        }.map { it.name }.toSet()
        assertEquals(
            setOf("Auth.kt", "PlatformWiring.kt"), files,
            "raw isOperator must be referenced ONLY in operatorEligible-def (Auth.kt) + reject-guard (PlatformWiring.kt); every god-token→OPERATOR grant of ANY shape routes operatorEligible. Found: $files",
        )
    }

    // ---- Layer-B closure (source-scan): all 3 secret-value holders are class-`private` (NOT internal/public) ----
    @Test
    fun layerB_allThreeSecretHolders_areClassPrivate() {
        val decl = Regex("""(private|internal|public)?\s*val operatorToken\s*:""")
        val nonPrivate = mutableListOf<String>()
        for (f in serverMain().filter { it.name in setOf("Auth.kt", "Secrets.kt", "CommRoutes.kt") }) {
            f.readLines().forEachIndexed { i, l ->
                val code = l.substringBefore("//")
                if (decl.containsMatchIn(code) && !code.trimStart().startsWith("private val operatorToken")) {
                    nonPrivate.add("${f.name}:${i + 1} → ${l.trim()}")
                }
            }
        }
        assertTrue(
            nonPrivate.isEmpty(),
            "the 3 operatorToken holders (TokenRegistry/Secrets/CommConfig) MUST be `private val` (NOT internal: :server is one module → == operatorToken would stay module-wide possible). Non-private: $nonPrivate",
        )
    }

    private fun serverMain(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val root = dir ?: fail("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
        return File(root, "server/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
