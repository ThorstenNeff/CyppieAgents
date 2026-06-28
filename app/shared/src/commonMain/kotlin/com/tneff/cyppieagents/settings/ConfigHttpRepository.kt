package com.tneff.cyppieagents.settings

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
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

/**
 * Live REST [ConfigRepository] against the CYP-96 config endpoints (PROJECT-SETTINGS §2), decoding the
 * shared `:core` wire DTOs ([RepoConfigView]/[RepoConfigRequest]/[ApiKeyView]/[ApiKeyRequest]) through
 * the shared [CommJson] so the contract can't drift. This is the CYP-85 **stub→real swap**: it replaces
 * `StubConfigRepository` as the default with **no UI/VM change** — the abstraction boundary the reviewer
 * checked.
 *
 * **Security preserved by structure:** the API key is write-only on the wire — [getApiKey] decodes
 * [ApiKeyView], which carries only `{set, masked}` and has no field for the plaintext key; the clear key
 * only travels outbound→server via [putApiKey]. A non-2xx maps to a [ConfigException] with the server's
 * reason code (`invalid_repo_url` / `invalid_api_key` / `operator_required` / `unauthorized`), so the
 * VM's honest error/gate disclosure runs unchanged. The repo URL is not a secret and round-trips in full.
 */
class ConfigHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ConfigRepository {

    override suspend fun getRepo(): RepoConfigState {
        val view = getDecoded("/api/config/repo", RepoConfigView.serializer())
        val url = view.url // local capture: :core property can't smart-cast across modules
        return if (view.configured && url != null) {
            RepoConfigState.Configured(url, view.branch ?: "main")
        } else {
            RepoConfigState.NotConfigured
        }
    }

    override suspend fun putRepo(url: String, branch: String): RepoConfigState.Configured {
        val view = putDecoded(
            "/api/config/repo", RepoConfigRequest.serializer(), RepoConfigRequest(url, branch), RepoConfigView.serializer(),
        )
        return RepoConfigState.Configured(view.url ?: url, view.branch ?: branch)
    }

    override suspend fun getApiKey(): ApiKeyState {
        val view = getDecoded("/api/config/apikey", ApiKeyView.serializer())
        return ApiKeyState(view.set, view.masked) // never any plaintext field — masked only
    }

    override suspend fun putApiKey(apiKey: String): ApiKeyState {
        val view = putDecoded(
            "/api/config/apikey", ApiKeyRequest.serializer(), ApiKeyRequest(apiKey), ApiKeyView.serializer(),
        )
        return ApiKeyState(view.set, view.masked)
    }

    private suspend fun <T> getDecoded(path: String, serializer: KSerializer<T>): T {
        val response = client.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(serializer, text)
    }

    private suspend fun <B, T> putDecoded(
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        responseSerializer: KSerializer<T>,
    ): T {
        val response = client.put("$baseUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(bodySerializer, body))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(responseSerializer, text)
    }

    /** Map a non-2xx to the server's reason code (the `{error:{code,message}}` envelope), else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    else -> "config_error"
                }
            }
        throw ConfigException(code)
    }
}
