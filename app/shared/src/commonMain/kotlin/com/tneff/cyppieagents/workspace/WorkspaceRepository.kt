package com.tneff.cyppieagents.workspace

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.WorkspaceMember
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * CYP-186 (roster fold) — the OPERATOR-only workspace roster data port. `GET /api/workspace/members` returns
 * `[WorkspaceMember]` for an OPERATOR and 403 (content-free) for a MEMBER.
 */
interface WorkspaceRepository {
    /** The roster. **Fail-closed:** any non-2xx (incl. the MEMBER 403) or transport error → empty (never a
     *  partial or leaked roster). */
    suspend fun members(): List<WorkspaceMember>
}

/** Live REST port against `GET /api/workspace/members`, decoding the shared `:core` DTO through [CommJson]. */
class WorkspaceHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : WorkspaceRepository {
    override suspend fun members(): List<WorkspaceMember> = try {
        val resp = client.get("$baseUrl/api/workspace/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (resp.status.isSuccess()) {
            CommJson.decodeFromString(ListSerializer(WorkspaceMember.serializer()), resp.bodyAsText())
        } else {
            emptyList() // 403 for a non-operator, any other non-2xx → fail-closed (no roster leak)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList() // transport error → fail-closed
    }
}

/** In-memory port for tests/dev. */
class StubWorkspaceRepository(private val members: List<WorkspaceMember> = emptyList()) : WorkspaceRepository {
    override suspend fun members(): List<WorkspaceMember> = members
}
