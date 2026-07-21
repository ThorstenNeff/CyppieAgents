package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.CommHttpException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * CYP-738 (live-wire, CYP-779 landed) — end-to-end proof that the newly-armed writable-AGENTS path GRIPS: a **real**
 * [HttpAgentWritableApi] hits a **real** embedded Ktor server at `GET /api/agents/writable` (bearer-gated,
 * `{agentIds:[…]}`), and the set flows through [AgentViewModel] into the derived [AgentComposerWritability]:
 * WRITABLE for an agent in the set, READ_ONLY for one not in it. This is what makes the composer tri-state LIVE
 * instead of the dormant unconditional-editable path, and it is the sibling of comm's
 * [com.tneff.cyppieagents.comm.WritableChannelsHttpE2eTest] (CYP-273). It does NOT re-prove the ACL logic — that
 * lives server-side and is mutation-proven in CYP-779; the server here just returns a canned set.
 *
 * The composer's fetch runs on [AgentViewModel]'s `viewModelScope` (SharingStarted.Eagerly), so Main is set to an
 * [UnconfinedTestDispatcher] (the `AgentTurnDeliveryTest` idiom); the settle is awaited (real network hop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp738AgentWritableHttpE2eTest {

    @BeforeTest fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private suspend fun awaitSettled(vm: AgentViewModel): AgentComposerWritability {
        withTimeout(5_000) { while (vm.composerWritability.value == AgentComposerWritability.UNKNOWN) delay(20) }
        return vm.composerWritability.value
    }

    @Test
    fun getAgentsWritable_drivesComposerWritability_endToEnd() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents/writable") {
                    when (call.request.header("Authorization")) {
                        // 'po' is writable; 'backend' is NOT — the {agentIds} shape (a JsonObject, not a bare array).
                        "Bearer op" -> call.respondText("""{"agentIds":["po"]}""", ContentType.Application.Json)
                        // Non-2xx WITH a well-formed, DECODABLE body — the fail-closed axis: the client must reject on
                        // STATUS, never decode this into a granted set (fail-OPEN).
                        "Bearer forbidden" -> call.respondText("""{"agentIds":["po","secret"]}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                        else -> call.respondText("""{"error":{"code":"unauthorized","message":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"

            // 1. The HTTP impl decodes {agentIds:[…]} (a JsonObject, unlike comm's bare String array).
            val api = HttpAgentWritableApi(client, base, token = "op")
            assertEquals(listOf("po"), api.writableAgents())

            // 2. Fail-closed at the wire: a 401 (no/invalid bearer) THROWS → the VM fail-closes to UNKNOWN (disabled).
            assertFails { HttpAgentWritableApi(client, base, token = "").writableAgents() }

            // 2b. The axis that matters: a non-2xx (403) whose body is a DECODABLE {agentIds} must STILL throw on
            // STATUS — never silently decode ["po","secret"] into a granted set (fail-OPEN). Guards `if (!isSuccess)
            // throw`; mutation: drop that check ⇒ this REDs (it would decode the 403 body as a granted set).
            val forbidden = assertFails { HttpAgentWritableApi(client, base, token = "forbidden").writableAgents() }
            assertIs<CommHttpException>(forbidden)
            assertEquals(403, forbidden.status, "must reject on status, not decode the body (fail-closed)")

            // 3. Wired end-to-end: the VM fetches through the real HTTP impl → the writable SET → composer tri-state.
            val poVm = AgentViewModel(emptySession(), agentId = "po", agentWritable = api)
            assertEquals(
                AgentComposerWritability.WRITABLE, awaitSettled(poVm),
                "'po' ∈ the writable set from GET /api/agents/writable → composer editable",
            )
            val backendVm = AgentViewModel(emptySession(), agentId = "backend", agentWritable = api)
            assertEquals(
                AgentComposerWritability.READ_ONLY, awaitSettled(backendVm),
                "'backend' ∉ the writable set → composer read-only (honest disable), never silently editable",
            )
        } finally {
            client.close()
            server.stop(100, 200)
        }
    }
}
