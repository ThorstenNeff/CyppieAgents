package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * CYP-273 — the **narrow client seam** for the caller's OWN writable channel set.
 *
 * Backing endpoint (Backend, CYP-273 part 1 — auto-versioned `/api` + `/api/v1`):
 * ```
 * GET /api/channels/writable → List<String>   // channel ids the resolved caller may write to RIGHT NOW
 * ```
 * Server-computed at the participant read tier (same auth as `GET /api/channels`); the invariant is
 * `writable ⊆ readable`. The server 403 stays the real enforcement point — this set only lets the UI
 * disable the composer honestly (comfort/honesty, not the enforce point).
 *
 * The shape (`List<String>`) is FIXED, so the client binding is built and tested against it now; wiring the
 * real HTTP implementation once the endpoint is merged is trivial (one shell-side swap). **Fail-closed** is
 * enforced at the call site ([CommViewModel.refreshWritable]): an error / not-yet-known result leaves the
 * writable set `null` → the composer is disabled, never optimistically open (CYP-288 fail-closed class).
 */
fun interface WritableChannelsApi {
    /** The channel ids the resolved caller may write to right now. Invariant: `writable ⊆ readable`. */
    suspend fun writableChannels(): List<String>
}

/**
 * CYP-273 (wiring) — the live HTTP implementation: `GET {baseUrl}/api/channels/writable` → `List<String>`,
 * bearer-authed like [CommRepository] (same participant read gate server-side). Decodes through the shared
 * [CommJson] so the wire contract can't drift. A non-2xx (e.g. 401/500) throws [CommHttpException] → the VM's
 * `refreshWritable` catches it and fail-closes `writable` to `null` (composer disabled), never optimistically
 * open. Token-agnostic: the caller injects the bearer (the operator token in the shell).
 */
class HttpWritableChannelsApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : WritableChannelsApi {
    override suspend fun writableChannels(): List<String> {
        val response = client.get("$baseUrl/api/channels/writable") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw CommHttpException(response.status.value, text)
        return CommJson.decodeFromString(ListSerializer(String.serializer()), text)
    }
}
