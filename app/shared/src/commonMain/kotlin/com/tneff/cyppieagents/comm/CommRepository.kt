package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Thrown when a comm REST call returns a non-2xx status (e.g. 403 ACL denial → comm_send_denied). */
class CommHttpException(val status: Int, val bodyText: String) : Exception("comm http $status")

/** The comm hub data port the [CommViewModel] depends on (REST in prod, faked in tests). */
interface CommApi {
    suspend fun channels(): List<Channel>
    suspend fun agents(): List<Agent>
    suspend fun messages(channelId: String, since: Long? = null): List<Message>
    suspend fun send(channelId: String, body: String, meta: MessageMeta? = null): Message
}

/**
 * Ktor REST client for the comm hub (Spec 02 §7), decoding through the shared [CommJson] so the
 * wire contract can't drift. Token-agnostic: the caller injects the bearer.
 *
 * NOTE (flagged to PO/backend): the read/send endpoints authorize via `requireAgent` (agent tokens
 * only). The CYP-17 "Viewer = Operator, sees all channels" path needs the server to accept the
 * **operator token** on `GET /api/channels` + messages (and POST as operator). Until then this
 * repository sees only the channels of whatever **agent** token it is given.
 */
class CommRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : CommApi {

    override suspend fun channels(): List<Channel> = getList("/api/channels", Channel.serializer())

    override suspend fun agents(): List<Agent> = getList("/api/agents", Agent.serializer())

    override suspend fun messages(channelId: String, since: Long?): List<Message> {
        val query = if (since != null) "?since=$since" else ""
        return getList("/api/channels/$channelId/messages$query", Message.serializer())
    }

    override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message {
        val response = client.post("$baseUrl/api/channels/$channelId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(SendMessageRequest.serializer(), SendMessageRequest(body, meta)))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(Message.serializer(), text)
    }

    private suspend fun <T> getList(path: String, element: KSerializer<T>): List<T> {
        val response = client.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(ListSerializer(element), text)
    }

    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (!response.status.isSuccess()) throw CommHttpException(response.status.value, text)
    }
}
