package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * CYP-273 (wiring) — end-to-end proof that the newly-wired writable path GRIPS: a **real** [HttpWritableChannelsApi]
 * hits a **real** embedded Ktor server at `GET /api/channels/writable` (bearer-gated, `List<String>`), and the set
 * flows through [CommViewModel] into the derived composer state (enabled for a writable channel, read-only for a
 * readable-but-not-writable one). This is what makes the tri-state canWrite machinery live instead of the interim
 * "everything writable" posture — and confirms the shell wiring (`AgentShell` → `HttpWritableChannelsApi`) is sound.
 */
class WritableChannelsHttpE2eTest {

    /** A fake comm read port: channels 'a','b' are both readable; only the writable SET decides the composer. */
    private class TwoChannelApi : CommApi {
        override suspend fun channels(): List<Channel> = listOf(
            Channel("a", "A", ChannelKind.HUB, listOf("operator")),
            Channel("b", "B", ChannelKind.HUB, listOf("operator")),
        )
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("srv", channelId, "operator", body, 9L)
    }

    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    @Test
    fun getWritable_drivesComposerState_endToEnd() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/channels/writable") {
                    when (call.request.header("Authorization")) {
                        // Only 'a' is writable (a subset of the readable {a,b}) — the wire form is a bare String array.
                        "Bearer op" -> call.respondText("""["a"]""", ContentType.Application.Json)
                        // A non-2xx WITH a well-formed, DECODABLE array body — the fail-closed axis that matters: the
                        // client must reject on STATUS, never decode this into a granted writable set (fail-OPEN).
                        "Bearer forbidden" -> call.respondText("""["a","secret"]""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                        // Same participant-read gate the real route uses (bearer required) — fail-closed without it.
                        else -> call.respondText("""{"error":{"code":"unauthorized","message":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(CIO)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"

            // 1. The HTTP impl decodes the server's List<String>.
            val api = HttpWritableChannelsApi(client, base, token = "op")
            assertEquals(listOf("a"), api.writableChannels())

            // 2. Fail-closed at the wire: a 401 (no/invalid bearer) THROWS → the VM will fail-close to disabled.
            assertFails { HttpWritableChannelsApi(client, base, token = "").writableChannels() }

            // 2b. The axis that matters (PO-Assistent hardening): a non-2xx (403) whose body is a DECODABLE array
            // must STILL throw a STATUS-based CommHttpException — never silently decode ["a","secret"] into a
            // granted writable set (that would fail-OPEN). Guards `if (!isSuccess) throw`; the 401 case (non-array
            // error body) can't — it throws on decode regardless. Mutation: drop the isSuccess check → this REDs.
            val forbidden = assertFails { HttpWritableChannelsApi(client, base, token = "forbidden").writableChannels() }
            assertIs<CommHttpException>(forbidden)
            assertEquals(403, forbidden.status, "must reject on status, not decode the array body (fail-closed)")

            // 3. Wired end-to-end: the VM fetches through the real HTTP impl → the writable SET → composer state.
            val vm = CommViewModel(TwoChannelApi(), IdleSource(), viewerId = "operator", writableChannels = api, scope = scope)
            withTimeout(5_000) { while (vm.state.value.writable == null) delay(20) }
            assertEquals(setOf("a"), vm.state.value.writable, "the writable set comes from GET /api/channels/writable")
            // 'a' auto-selected → in the writable set → composer editable.
            assertEquals(true, vm.state.value.canWrite, "writable channel 'a' → composer enabled")
            // 'b' is readable but NOT writable → known read-only → composer shows the read-only hint (canWrite=false).
            vm.select("b")
            assertEquals(false, vm.state.value.canWrite, "readable-but-not-writable 'b' → composer read-only, not open")
        } finally {
            scope.cancel()
            client.close()
            server.stop(100, 200)
        }
    }
}
