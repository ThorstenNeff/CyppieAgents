package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * INDEPENDENT QA pass for the S4 comm hub (CYP-8/CYP-9 re-verify) — adversarial probes for edges
 * NOT covered by CommHubAclTest/PersistenceTest. Evidence-driven: each probe asserts the *intended*
 * security/AC behavior; a red probe is a finding, not noise.
 *
 * Authored by QA (tester) as a second, independent witness — does not trust "all green".
 */
class S4ServerQaProbesTest {

    private fun config(store: MessageStore = InMemoryMessageStore()) = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = store,
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    /** Read and write are enforced INDEPENDENTLY: canWrite=true + canRead=false ⇒ POST ok, GET 403, inbox empty. */
    @Test
    fun asymmetricAcl_writeAllowedButReadForbidden() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // Operator: backend may write its spoke but NOT read it.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "backend", canRead = false, canWrite = true))
        }
        assertEquals(HttpStatusCode.OK, put.status)

        // write still allowed
        val post = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("write-only"))
        }
        assertEquals(HttpStatusCode.Created, post.status)

        // read of the same channel now forbidden
        val read = client.get("/api/channels/po-backend/messages") { bearerAuth("tok-backend") }
        assertEquals(HttpStatusCode.Forbidden, read.status)

        // and it is excluded from backend's own inbox
        val inbox: List<Message> = client.get("/api/inbox") { bearerAuth("tok-backend") }.body()
        assertTrue(inbox.isEmpty(), "write-only channel must not appear in own inbox")

        // but po (full member) can still read it
        val poRead: List<Message> = client.get("/api/channels/po-backend/messages") { bearerAuth("tok-po") }
            .body<List<DeliveredMessage>>().map { it.message } // CYP-744: unwrap the DeliveredMessage envelope
        assertEquals(listOf("write-only"), poRead.map { it.body })
    }

    /** Posting to a channel that does not exist must fail CLOSED (403), never 404/500/Created. */
    @Test
    fun postToNonexistentChannel_failsClosed403() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.post("/api/channels/po-nonexistent/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("ghost"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    /** Reading a non-existent channel must also fail closed (403, not 404). */
    @Test
    fun readNonexistentChannel_failsClosed403() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.get("/api/channels/po-nonexistent/messages") { bearerAuth("tok-backend") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    /** Revoke then re-grant restores the ability at runtime (S7 preview; enforcement is live). */
    @Test
    fun revokeThenRegrant_restoresWrite() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        suspend fun setWrite(canWrite: Boolean) = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "backend", canRead = true, canWrite = canWrite))
        }
        suspend fun post() = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("x"))
        }
        assertEquals(HttpStatusCode.OK, setWrite(false).status)
        assertEquals(HttpStatusCode.Forbidden, post().status)
        assertEquals(HttpStatusCode.OK, setWrite(true).status)
        assertEquals(HttpStatusCode.Created, post().status, "re-granting canWrite must restore posting without restart")
    }

    /** Malformed JSON body ⇒ 400 with the uniform error envelope, never a leaked 500. */
    @Test
    fun malformedJsonBody_returns400WithErrorEnvelope() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody("{ this is not json ")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val text = res.bodyAsText()
        assertTrue(text.contains("\"error\""), "expected error envelope, got: $text")
        assertTrue(text.contains("\"code\""), "expected error.code, got: $text")
    }

    /** Secret-shaped content is redacted on egress (Gate #3) end-to-end over HTTP. */
    @Test
    fun secretInBody_isMaskedOnEgress() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val secret = "sk-ant-abcd1234efgh5678"
        client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("leaking $secret now"))
        }
        val msgs: List<Message> = client.get("/api/channels/po-backend/messages") { bearerAuth("tok-po") }
            .body<List<DeliveredMessage>>().map { it.message } // CYP-744: unwrap the DeliveredMessage envelope
        val body = msgs.single().body
        assertFalse(body.contains(secret), "raw secret must not be persisted/served: $body")
        assertTrue(body.contains("***REDACTED***"), "expected redaction marker, got: $body")
    }

    /** `since` is strictly-greater: a message is excluded at its own ts and included just before it. */
    @Test
    fun sinceFilter_isStrictlyGreater() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val created: Message = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("m1"))
        }.body<DeliveredMessage>().message // CYP-744: unwrap

        val atTs: List<Message> = client.get("/api/channels/po-backend/messages?since=${created.ts}") { bearerAuth("tok-po") }
            .body<List<DeliveredMessage>>().map { it.message }
        assertFalse(atTs.any { it.id == created.id }, "since=ts must EXCLUDE the message at exactly ts")

        val justBefore: List<Message> = client.get("/api/channels/po-backend/messages?since=${created.ts - 1}") { bearerAuth("tok-po") }
            .body<List<DeliveredMessage>>().map { it.message }
        assertTrue(justBefore.any { it.id == created.id }, "since=ts-1 must INCLUDE the message")
    }

    /**
     * CYP-18 contract change: the operator token IS now accepted on comm reads as a privileged
     * AclMatrix participant (member of every channel) — the human/UI viewer. It is NOT a bypass:
     * the same AclMatrix filter applies, so the operator sees all (readable) channels.
     */
    @Test
    fun operatorToken_isAcceptedAsPrivilegedParticipant_onChannels() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.get("/api/channels") { bearerAuth("tok-op") }
        // Substance, not just acceptance (CYP-18 QA hardening): a privileged AclMatrix participant
        // is a member of EVERY channel, so it must see ALL of them — not an empty 200, not a subset.
        assertEquals(HttpStatusCode.OK, res.status, "CYP-18: operator token is an accepted privileged participant on comm reads")
        val channels: List<Channel> = res.body()
        assertEquals(
            setOf("po-frontend", "po-backend"),
            channels.map { it.id }.toSet(),
            "operator (privileged participant) must see ALL channels, not just be accepted",
        )
    }
}
