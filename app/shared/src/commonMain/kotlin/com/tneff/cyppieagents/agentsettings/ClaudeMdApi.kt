package com.tneff.cyppieagents.agentsettings

import com.tneff.cyppieagents.CommJson
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * CYP-310 — the live worktree CLAUDE.md, decoupled from the stored persona. The Agent-settings field now reads
 * the ACTUAL file and writes it via an explicit hard overwrite (no auto-save), with optimistic-concurrency so an
 * external change (e.g. the agent editing its own CLAUDE.md) can't be clobbered silently.
 *
 * Backend contract (v3, `:core` DTOs land separately — the client parses the FIXED JSON shape until then):
 *  - `GET  /api/agents/{id}/claude-md` → 200 `{agentId, content, exists, version}` (live file; `content=""` +
 *    `exists=false` when the file/worktree is absent). 404 `agent_not_found` · 409 `agent_not_local` (remote,
 *    CYP-197). **Auth: participant read** (same posture as `GET /api/agents/{id}`).
 *  - `POST /api/agents/{id}/claude-md` body `{content, expectedVersion}` → 200 `{…,version}` (echo, `exists=true`,
 *    mkdirs; HARD overwrite). 409 `claude_md_stale` when [ClaudeMdView.version] moved (external edit). **Auth:
 *    operator.** The server is the enforcement point; the client disable/version-check is comfort/honesty.
 */
data class ClaudeMdView(
    val agentId: String,
    val content: String,
    /** false = the file/worktree does not exist yet (a first write CREATES it — not a destructive overwrite). */
    val exists: Boolean,
    /** Content-hash for optimistic concurrency: echoed back as `expectedVersion` on write; a mismatch → 409 stale. */
    val version: String,
)

/** A CLAUDE.md call was rejected. [code] = the server reason: `agent_not_found`, `agent_not_local`, `claude_md_stale`. */
class ClaudeMdException(val code: String) : Exception(code)

/** The live-CLAUDE.md data port the settings VM depends on (REST in prod, faked in tests). */
interface ClaudeMdApi {
    /** Read the live file. Throws [ClaudeMdException] on `agent_not_found` (404) / `agent_not_local` (409). */
    suspend fun get(agentId: String): ClaudeMdView

    /** Hard-overwrite the file, guarded by [expectedVersion]. Throws `claude_md_stale` (409) on an external change. */
    suspend fun update(agentId: String, content: String, expectedVersion: String): ClaudeMdView
}

/** Ktor REST client. Token-agnostic: the caller injects the bearer (participant read; operator for the write). */
class ClaudeMdHttpApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ClaudeMdApi {

    override suspend fun get(agentId: String): ClaudeMdView {
        val response = client.get("$baseUrl/api/agents/$agentId/claude-md") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseOrThrow(response)
    }

    override suspend fun update(agentId: String, content: String, expectedVersion: String): ClaudeMdView {
        val body = buildJsonObject {
            put("content", content)
            put("expectedVersion", expectedVersion)
        }
        val response = client.post("$baseUrl/api/agents/$agentId/claude-md") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(JsonObject.serializer(), body))
        }
        return parseOrThrow(response)
    }

    /** 2xx → [ClaudeMdView]; else map the `{error:{code}}` envelope to a [ClaudeMdException] (fail-closed upstream). */
    private suspend fun parseOrThrow(response: HttpResponse): ClaudeMdView {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val code = runCatching {
                CommJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            }.getOrNull() ?: "claude_md_error"
            throw ClaudeMdException(code)
        }
        val o = CommJson.parseToJsonElement(text).jsonObject
        return ClaudeMdView(
            agentId = o["agentId"]?.jsonPrimitive?.content ?: "",
            content = o["content"]?.jsonPrimitive?.content ?: "",
            exists = o["exists"]?.jsonPrimitive?.boolean ?: false,
            version = o["version"]?.jsonPrimitive?.content ?: "",
        )
    }
}
