package com.tneff.cyppieagents.project

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer

/**
 * Live REST [ProjectRepository] against the CYP-91/92 registry endpoints, decoding the shared `:core` wire
 * DTOs through the shared [CommJson] so the contract can't drift. The **stub→real swap** (pattern CYP-85/90):
 * replaces `StubProjectRepository` as the default with **no UI/VM change** — the abstraction boundary holds.
 *
 * All endpoints are operator-gated (every call carries the operator token; without one the server 401/403s
 * and the VM fails closed). Switching is `POST /api/projects/switch` (a dedicated path, never `/active`, so
 * a project whose id is `active` can't shadow the route). A non-2xx maps to a [ProjectException] with the
 * server's reason code from the `{error:{code,message}}` envelope — so the 409 guardrails
 * (`active_project_protected`/`last_project`) carry through to the UI's inline delete-safety mapping.
 */
class HttpProjectRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ProjectRepository {

    override suspend fun list(): ProjectsView =
        get("/api/projects", ProjectsView.serializer())

    override suspend fun create(request: CreateProjectRequest): Project {
        val response = client.post("$baseUrl/api/projects") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(CreateProjectRequest.serializer(), request))
        }
        return decode(response, Project.serializer())
    }

    override suspend fun switchActive(projectId: String): ProjectsView {
        val response = client.post("$baseUrl/api/projects/switch") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(SwitchActiveRequest.serializer(), SwitchActiveRequest(projectId)))
        }
        return decode(response, ProjectsView.serializer())
    }

    override suspend fun rename(id: String, request: RenameProjectRequest): Project {
        val response = client.put("$baseUrl/api/projects/$id") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(RenameProjectRequest.serializer(), request))
        }
        return decode(response, Project.serializer())
    }

    override suspend fun delete(id: String, deleteWorktrees: Boolean) {
        val response = client.delete("$baseUrl/api/projects/$id?deleteWorktrees=$deleteWorktrees") { auth() }
        ensureSuccess(response, response.bodyAsText()) // ProjectDeleteReceipt body is not consumed in S13 (counts = Fast-Follow)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        val response = client.get("$baseUrl$path") { auth() }
        return decode(response, serializer)
    }

    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T {
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(serializer, text)
    }

    /** Map a non-2xx to the server's reason code (the `{error:{code,message}}` envelope), else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    404 -> "project_not_found"
                    else -> "project_error"
                }
            }
        throw ProjectException(code)
    }
}
