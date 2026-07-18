package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-710 — STEP 1 of the 6×65 matrix: prove the six principal CLASSES are actually constructible and actually
 * resolve to what they are supposed to be, before 390 cells are driven through them.
 *
 * ### Why this exists as its own test
 * The matrix's entire value depends on each row genuinely being the principal it claims to be. If, say, the
 * kill-switch class silently resolves as a plain operator, the matrix would still be green and would still be
 * measuring — just the wrong thing. That is the fixture-fidelity failure mode this whole ticket exists to close,
 * so it gets pinned first and separately rather than assumed inside the big run.
 *
 * ### The three probes
 * Each class is characterised against one op per enforcement mechanism, chosen to be env-independent and
 * body-free (so a status can never be an artefact of a malformed request):
 *  - `GET /api/projects` — Tier.OPERATOR, gated STRUCTURALLY by `authenticatedApi` (mechanism A).
 *  - `GET /api/channels` — Tier.PARTICIPANT, gated PER-HANDLER by `requireCommReader` (mechanism B).
 *  - `GET /api/events`   — Tier.MEMBER, structural, the middle tier of the enforced lattice.
 *
 * ### What is deliberately NOT used as the oracle
 * `GET /api/auth/me` looks like the natural whoami probe and is the wrong instrument here: `resolveAuthState`
 * (Principal.kt:142) checks `tokens.isOperator(bearer)` FIRST and returns OPERATOR **without consulting the
 * kill-switch**, so it reports OPERATOR for a credential that every structurally-gated op rejects. Classes are
 * therefore characterised by what the AUTHORIZATION surface does, never by what the whoami says.
 */
class Cyp710PrincipalClassesTest {

    private val opToken = "tok-op"
    private val agentToken = "tok-backend"

    private data class Probe(val label: String, val method: String, val path: String)

    private val probes = listOf(
        Probe("operator-structural", "GET", "/api/projects"),
        Probe("participant-handler", "GET", "/api/channels"),
        Probe("member-structural", "GET", "/api/events"),
    )

    /** A status that proves the auth layer ADMITTED the caller (2xx, or a non-auth error like 400/404/409). */
    private fun HttpStatusCode.isAdmitted(): Boolean = value != 401 && value != 403

    @Test
    fun theSixPrincipalClasses_resolveAsIntended() = testApplication {
        val db = Files.createTempFile("cyp710-principals", ".db")
        val roles = SqliteRoleStore(db, bootstrapOperatorId = "alice") // CYP-196: alice is the pinned OPERATOR
        val participantTokens = ParticipantTokenStore { 1_000L }
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true),
                    "sess-carol" to ResolvedIdentity("carol", verified = true),
                ),
            ),
            roles = roles,
            nowMs = { 1_000L },
            participantTokens = participantTokens,
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"))
        }
        startApplication()

        // The role rows exist only after `ensureAssigned`, which the public whoami triggers. ORDER MATTERS: the
        // single-OPERATOR slot must go to the PINNED identity, so alice is bumped first.
        suspend fun bump(session: String) = client.request("/api/auth/me") { header("X-Session-Token", session) }
        bump("sess-alice")
        bump("sess-carol")

        val participantToken = participantTokens.mint("byo-reader")

        suspend fun probeAs(p: Probe, apply: HttpRequestBuilder.() -> Unit): HttpStatusCode =
            client.request(p.path) { method = HttpMethod.parse(p.method); apply() }.status

        suspend fun row(apply: HttpRequestBuilder.() -> Unit): Map<String, HttpStatusCode> {
            val out = linkedMapOf<String, HttpStatusCode>()
            for (p in probes) out[p.label] = probeAs(p, apply)
            return out
        }

        val observed = linkedMapOf<String, Map<String, HttpStatusCode>>()
        observed["a-MachineOperator"] = row { header("Authorization", "Bearer $opToken") }
        observed["b-MachineAgent(id)"] = row { header("Authorization", "Bearer $agentToken") }
        observed["d-HumanOPERATOR"] = row { header("X-Session-Token", "sess-alice") }
        observed["e-HumanMEMBER"] = row { header("X-Session-Token", "sess-carol") }
        observed["f-participant"] = row { header("Authorization", "Bearer $participantToken") }
        observed["z-noCredential"] = row { }

        // Report the whole characterisation, so a surprise is readable rather than a bare assertion failure.
        val report = observed.entries.joinToString("\n") { (cls, row) ->
            "  %-22s %s".format(cls, row.entries.joinToString("  ") { "${it.key}=${it.value.value}" })
        }
        println("CYP710-PRINCIPAL-CLASSES\n$report")

        // ---- the characterisation assertions (each states the CLASS, not a specific code where it is a range) ----

        // (a) MachineOperator is admitted everywhere — it is the top of both mechanisms.
        val a = observed.getValue("a-MachineOperator")
        assertTrue(a.getValue("operator-structural").isAdmitted(), "MachineOperator must pass the structural OPERATOR gate, got ${a["operator-structural"]}")
        assertTrue(a.getValue("participant-handler").isAdmitted(), "MachineOperator must pass requireCommReader, got ${a["participant-handler"]}")

        // (b) MachineAgent(id) is a MEMBER-equivalent machine: refused by the OPERATOR gate, admitted by the
        //     ACL-subject resolver. This is the pair that makes the two mechanisms visibly different.
        val b = observed.getValue("b-MachineAgent(id)")
        assertEquals(HttpStatusCode.Forbidden, b.getValue("operator-structural"), "an agent token must be 403 (credential known, tier insufficient) at a structural OPERATOR op")
        assertTrue(b.getValue("participant-handler").isAdmitted(), "an agent token IS a comm reader, got ${b["participant-handler"]}")

        // (d) the pinned human is OPERATOR — proves the bump order took and CYP-196 granted the slot.
        val d = observed.getValue("d-HumanOPERATOR")
        assertTrue(d.getValue("operator-structural").isAdmitted(), "the PINNED human must be OPERATOR — if this is 403 the bump order or the pin is wrong, and every operator row of the matrix would be measuring a MEMBER")

        // (e) the unpinned human is MEMBER: refused by OPERATOR, admitted by MEMBER.
        val e = observed.getValue("e-HumanMEMBER")
        assertEquals(HttpStatusCode.Forbidden, e.getValue("operator-structural"), "an unpinned human must be MEMBER → 403 at an OPERATOR op")
        assertTrue(e.getValue("member-structural").isAdmitted(), "a MEMBER must pass the MEMBER tier, got ${e["member-structural"]}")

        // (f) participant:<subject> is roleless: it is NOT a role principal at all, so the structural gate sees an
        //     UNKNOWN bearer → 401 (not 403). That asymmetry vs (b) is a real property of the model and is pinned
        //     here rather than smoothed over.
        val f = observed.getValue("f-participant")
        assertEquals(HttpStatusCode.Unauthorized, f.getValue("operator-structural"), "a participant token is an unknown bearer to the ROLE layer → 401, not 403 (asymmetry vs an agent token)")
        assertTrue(f.getValue("participant-handler").isAdmitted(), "a participant token must be admitted by the ACL-subject resolver, got ${f["participant-handler"]}")

        // (z) the no-credential control — without it, every "denied" above could be a route that simply 404s.
        val z = observed.getValue("z-noCredential")
        assertTrue(!z.getValue("operator-structural").isAdmitted(), "no-credential must be denied at an OPERATOR op, got ${z["operator-structural"]}")
        assertTrue(!z.getValue("participant-handler").isAdmitted(), "no-credential must be denied at a PARTICIPANT op, got ${z["participant-handler"]}")

        // ---- §8 in miniature: every probe must be REACHABLE, or the denials above prove nothing ----
        for (p in probes) {
            val anyAdmitted = observed.values.any { it.getValue(p.label).isAdmitted() }
            assertTrue(
                anyAdmitted,
                "INCONCLUSIVE, not a pass: no principal class was admitted at ${p.method} ${p.path}, so its denials " +
                    "are indistinguishable from the route not existing at all",
            )
        }
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf(agentToken to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp710-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
