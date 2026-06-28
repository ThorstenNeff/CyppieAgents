package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Thrown when an ACL REST call returns non-2xx (e.g. 403 operator/PO-lockout denial → revert to hub). */
class AclHttpException(val status: Int, val bodyText: String) : Exception("acl http $status")

/** The ACL hub data port the [AclViewModel] depends on (REST in prod, faked in tests). */
interface AclApi {
    suspend fun channels(): List<Channel>
    suspend fun agents(): List<Agent>
    suspend fun acl(channelId: String? = null, agentId: String? = null): List<AclEntry>
    suspend fun setAcl(entry: AclEntry): AclEntry
}

/**
 * Ktor REST client for the ACL endpoints (Spec 02 §7: `GET`/`PUT /api/acl`), decoding through the
 * shared [CommJson] so the wire contract can't drift. Operator-token-bound: `PUT /api/acl` is
 * operator-only (the server 403s otherwise) and the UI **relies on that server enforcement** — it
 * never authorizes locally. PO-lockout enforcement is the server's job (CYP-49); a rejected PUT is
 * handled by reverting the [AclViewModel] to the hub state.
 */
class AclRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : AclApi {

    override suspend fun channels(): List<Channel> = getList("/api/channels", Channel.serializer())

    override suspend fun agents(): List<Agent> = getList("/api/agents", Agent.serializer())

    override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> {
        val params = buildList {
            if (channelId != null) add("channelId=$channelId")
            if (agentId != null) add("agentId=$agentId")
        }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return getList("/api/acl$query", AclEntry.serializer())
    }

    override suspend fun setAcl(entry: AclEntry): AclEntry {
        val response = client.put("$baseUrl/api/acl") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(AclEntry.serializer(), entry))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(AclEntry.serializer(), text)
    }

    private suspend fun <T> getList(path: String, element: KSerializer<T>): List<T> {
        val response = client.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(ListSerializer(element), text)
    }

    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (!response.status.isSuccess()) throw AclHttpException(response.status.value, text)
    }
}
