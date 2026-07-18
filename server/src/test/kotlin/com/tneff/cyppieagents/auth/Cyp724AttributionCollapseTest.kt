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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-724 — is the ACTOR recoverable from a posted message, or do distinct principals collapse to one `from`?
 *
 * CYP-80 §1 C.1 expects the human to stay distinguishable. The CYP-722 write probe already showed the machine
 * operator TOKEN posting as `from:"operator"`. The open question is the other half: does a HUMAN OPERATOR also
 * post as `"operator"` — in which case the comm record cannot tell a person from a credential — or does it carry
 * something identity-bearing?
 *
 * ### Object proof, not a code reading
 * Every row here posts a REAL message through the REAL route and reads the `from` field off the STORED object.
 * Deliberate: code reading has been wrong twice today (the forwarder race that never fired; a `replay=1`
 * recommendation the measurement refuted), so the artefact — the persisted message — is the oracle.
 *
 * Marker bodies make each row individually attributable in the output, so a surprise is readable rather than a
 * bare boolean.
 */
class Cyp724AttributionCollapseTest {

    private val opToken = "tok-op"
    private val agentToken = "tok-backend"

    @Test
    fun doTokenAndHumanCollapseToTheSameFrom() = testApplication {
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true), // pinned -> OPERATOR
                    "sess-carol" to ResolvedIdentity("carol", verified = true), // -> MEMBER
                ),
            ),
            roles = SqliteRoleStore(Files.createTempFile("cyp724", ".db"), bootstrapOperatorId = "alice"),
            nowMs = { 1_000L },
        )
        application {
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"))
        }
        startApplication()

        client.request("/api/auth/me") { header("X-Session-Token", "sess-alice") } // pinned first
        client.request("/api/auth/me") { header("X-Session-Token", "sess-carol") }

        suspend fun post(marker: String, cred: HttpRequestBuilder.() -> Unit): Pair<Int, String> {
            val r = client.request("/api/channels/po-backend/messages") {
                method = HttpMethod.Post
                header("Content-Type", "application/json")
                cred()
                setBody("""{"body":"$marker"}""")
            }
            val from = Regex("\"from\"\\s*:\\s*\"([^\"]*)\"").find(r.bodyAsText())?.groupValues?.get(1) ?: "<none>"
            return r.status.value to from
        }

        val machineOperator = post("cyp724-machine-operator") { header("Authorization", "Bearer $opToken") }
        val humanOperator = post("cyp724-human-operator") { header("X-Session-Token", "sess-alice") }
        val humanMember = post("cyp724-human-member") { header("X-Session-Token", "sess-carol") }
        val machineAgent = post("cyp724-machine-agent") { header("Authorization", "Bearer $agentToken") }

        println(
            "CYP724-ATTRIBUTION\n" +
                "  machine-operator(token)  status=${machineOperator.first} from=${machineOperator.second}\n" +
                "  human-OPERATOR(session)  status=${humanOperator.first} from=${humanOperator.second}\n" +
                "  human-MEMBER(session)    status=${humanMember.first} from=${humanMember.second}\n" +
                "  machine-agent(token)     status=${machineAgent.first} from=${machineAgent.second}",
        )

        // Both operator-class writes must have LANDED, or the comparison below compares nothing.
        assertTrue(
            machineOperator.first in 200..299 && humanOperator.first in 200..299,
            "precondition: both operator-class posts must succeed for the attribution comparison to mean anything " +
                "(machine=${machineOperator.first}, human=${humanOperator.first})",
        )

        val collapsed = machineOperator.second == humanOperator.second
        if (collapsed) {
            println(
                "CYP724-FINDING ATTRIBUTION COLLAPSE: the machine operator TOKEN and a HUMAN OPERATOR both post as " +
                    "from=\"${humanOperator.second}\". The stored message cannot distinguish a person from a " +
                    "credential, so an operator action in the comm record is not attributable to an actor " +
                    "(CYP-80 §1 C.1 expects the human to stay distinguishable).",
            )
        } else {
            println(
                "CYP724-NO-COLLAPSE machine=\"${machineOperator.second}\" human=\"${humanOperator.second}\" — the " +
                    "human remains distinguishable; CYP-80 §1 C.1 holds on this path.",
            )
        }

        // Reported, not adjudicated: whether collapsing is acceptable is an owner call. What IS asserted is the
        // control that must hold either way — a machine AGENT must never be attributed to the operator identity.
        assertTrue(
            machineAgent.second != machineOperator.second || machineAgent.first !in 200..299,
            "an agent token's post must not be attributed to the operator identity " +
                "(agent from=${machineAgent.second}, operator from=${machineOperator.second})",
        )
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
        val gitRoot = Files.createTempDirectory("cyp724-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
