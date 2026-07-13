package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.CommJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-526 — the rendezvous register POST hits the hub's OWN edge (like admit), so it shares the CYP-524 boot-window
 * hardening via the single-sourced [cpRetry] + [cpJsonGuard]: a transient edge 502 is retried; a non-JSON edge body
 * yields a diagnostic `null` (not an opaque `SourceByteReadChannel` from a blind `.body()`).
 */
class Cyp526RendezvousRegisterResilienceTest {

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun htmlHeaders() = headersOf(HttpHeaders.ContentType, "text/html")
    private fun bindingJson(id: String) =
        CommJson.encodeToString(RendezvousResolveResponse(binding = RendezvousBinding(rendezvousId = id, relayUrl = "wss://relay")))

    @Test
    fun register_retriesTransientEdge502_thenReturnsTheBindingId() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, htmlHeaders())
            else respond(bindingJson("rzv-xyz"), HttpStatusCode.OK, jsonHeaders())
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(CommJson) } }
        val reg = HubRendezvousRegistrar("http://cp.test", http, "hub-1", { "op" },
            retry = AdmissionRetry(maxAttempts = 5, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
        assertEquals("rzv-xyz", reg.register(), "register retries transient edge-502s until the CP serves the binding")
        assertEquals(3, calls, "2 transient + 1 ok")
    }

    @Test
    fun register_nonJsonEdge_returnsDiagnosticNull_notOpaqueSourceByteReadChannel() = runBlocking {
        val engine = MockEngine { respond("<html>502</html>", HttpStatusCode.BadGateway, htmlHeaders()) }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(CommJson) } }
        val reg = HubRendezvousRegistrar("http://cp.test", http, "hub-1", { "op" },
            retry = AdmissionRetry(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
        assertNull(reg.register(), "a non-JSON edge → guarded diagnostic null (bounded retry exhausts), never a blind opaque throw")
    }

    @Test
    fun register_noOperatorCredential_isNull_failClosed() = runBlocking {
        val engine = MockEngine { respond(bindingJson("rzv"), HttpStatusCode.OK, jsonHeaders()) }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(CommJson) } }
        val reg = HubRendezvousRegistrar("http://cp.test", http, "hub-1", { null }, sleep = {})
        assertNull(reg.register(), "no operator credential → fail-closed null (no dial)")
    }
}
