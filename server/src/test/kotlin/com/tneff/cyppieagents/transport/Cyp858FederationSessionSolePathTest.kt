package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-858 (§9.3 sole-path GUARD) — a [FederationSession] is constructible ONLY through the gated
 * [FederationSession.open]; there is no session side-door. Two axes, each mutation-proven (PL requirement):
 *  - **behavioral:** `open` yields a session IFF the [FederationAdmissionGate] ADMITs — a DENY yields `null`;
 *  - **structural:** the constructor is `private` (not `internal`) and no raw `FederationSession(` construction
 *    exists outside its own file, so the admission check is unavoidable.
 *
 * Together these make every dark-built federation piece physically INERT until arming: nothing can produce a live
 * (eventually off-loopback) session except through the gate.
 */
@OptIn(ExperimentalFederation::class)
class Cyp858FederationSessionSolePathTest {

    private object NoopTransport : FederationPeerTransport {
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun send(frame: ByteArray) {}
        override suspend fun close() {}
    }

    // --- behavioral sole-path: a session exists ONLY when the gate ADMITs -----------------------------------------

    /** Positive control: an ADMITting gate (enabled + TRUSTED) DOES yield a session — else the deny teeth are vacuous. */
    @Test
    fun open_admittedPeer_returnsSession() {
        val gate = FederationAdmissionGate(federationEnabled = true)
        assertNotNull(FederationSession.open(gate, HubIssuerTrust.TRUSTED, NoopTransport))
    }

    /** PL sole-path tooth: federation DISABLED → even a TRUSTED peer gets NO session. MUT `open` ignore gate → RED. */
    @Test
    fun open_gateDeniesByPosture_returnsNull() {
        val disabled = FederationAdmissionGate(federationEnabled = false)
        assertNull(FederationSession.open(disabled, HubIssuerTrust.TRUSTED, NoopTransport))
    }

    /** PL sole-path tooth: enabled but NOT_TRUSTED / absent posture → NO session. MUT `open` ignore gate → RED. */
    @Test
    fun open_gateDeniesByTrust_returnsNull() {
        val enabled = FederationAdmissionGate(federationEnabled = true)
        assertNull(FederationSession.open(enabled, HubIssuerTrust.NOT_TRUSTED, NoopTransport))
        assertNull(FederationSession.open(enabled, HubIssuerTrust.REMOTE_NOT_CONFIGURED, NoopTransport))
        assertNull(FederationSession.open(enabled, null, NoopTransport))
    }

    // --- structural sole-path: private ctor + no off-gate construction --------------------------------------------

    /**
     * The constructor MUST be declared `private` (NOT `internal` — `:server` is one module, so `internal` would let
     * any same-module site build one off-gate). MUT the constructor to public/internal → RED.
     */
    @Test
    fun federationSessionConstructor_isPrivate() {
        val src = federationSessionSource().readText()
        assertTrue(
            Regex("""class\s+FederationSession\s+private\s+constructor""").containsMatchIn(src),
            "FederationSession must declare a PRIVATE constructor (§9.3 sole-path guard). Found declaration:\n" +
                src.lineSequence().firstOrNull { "class FederationSession" in it },
        )
    }

    /**
     * A raw `FederationSession(` construction may appear ONLY inside FederationSession.kt (the `open` factory). Any
     * such construction elsewhere in `:server` is an off-gate side-door. MUT add `FederationSession(t)` in another
     * file → this reds. (This guard test is excluded — it names the pattern but constructs nothing off-gate.)
     */
    @Test
    fun noOffGateConstruction_outsideFederationSessionFile() {
        val ctor = Regex("""FederationSession\(""")
        val exempt = setOf("FederationSession.kt", "Cyp858FederationSessionSolePathTest.kt")
        val offenders = serverKtFiles().filter { f ->
            f.name !in exempt && f.readLines().any { l ->
                val t = l.trimStart()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@any false
                ctor.containsMatchIn(l.substringBefore("//"))
            }
        }.map { it.name }
        assertEquals(emptyList(), offenders, "off-gate FederationSession construction found in: $offenders")
    }

    // --- file helpers (repo-root relative, CYP-829 pattern) -------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: fail("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
    }

    private fun federationSessionSource(): File =
        File(repoRoot(), "server/src/main/kotlin/com/tneff/cyppieagents/transport/FederationSession.kt")

    private fun serverKtFiles(): List<File> =
        File(repoRoot(), "server/src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
