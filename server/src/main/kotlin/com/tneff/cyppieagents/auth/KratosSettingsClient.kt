package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** The raw Kratos settings-flow outcome — passed through unchanged to the caller (thin shim). */
data class SettingsOutcome(val status: Int, val body: String)

/**
 * CYP-181 / P2.4 — the **thin shim** that mediates a logged-in member's Kratos **settings** flow
 * (change password / email). It creates a native settings flow authenticated with the CALLER's OWN session
 * credential (forwarded in the ONE header its source dictates, never both — like
 * [KratosIdentityProvider]) and submits the requested change. **ALL security is delegated to Kratos**
 * (session validity, `privileged_session_max_age`, password hashing, re-verification on email change) —
 * the shim adds none.
 *
 * **(a/C) secret hygiene:** the new password / email are **passed straight through** to Kratos and are
 * NEVER logged, stored, or included in any error/log line — only Kratos statuses are logged. **(c/B)
 * self-scoped:** there is no identity parameter; the acted-on identity is the one the session belongs to
 * (Kratos resolves it from the forwarded session), so a member can only ever change their OWN account.
 * Bounded [timeoutMs] so a hung Kratos never hangs the request.
 */
class KratosSettingsClient(
    private val publicBaseUrl: String,
    private val client: HttpClient = HttpClient(CIO) { install(HttpTimeout) },
    private val timeoutMs: Long = 5_000,
) {
    private val log = LoggerFactory.getLogger("auth.kratos.settings")
    private val json = Json { ignoreUnknownKeys = true }
    private val base = publicBaseUrl.trimEnd('/')

    /** Change the caller's password via the Kratos settings `password` method. Never logs [newPassword]. */
    suspend fun changePassword(session: SessionCredential, newPassword: String): SettingsOutcome =
        submit(session, buildJsonObject { put("method", "password"); put("password", newPassword) }.toString())

    /** Change the caller's email via the Kratos settings `profile` method. Never logs [newEmail]. */
    suspend fun changeEmail(session: SessionCredential, newEmail: String): SettingsOutcome =
        submit(session, buildJsonObject { put("method", "profile"); put("traits", buildJsonObject { put("email", newEmail) }) }.toString())

    private suspend fun submit(session: SessionCredential, payload: String): SettingsOutcome {
        return try {
            val flow = get("$base/self-service/settings/api", session)
            val action = json.parseToJsonElement(flow.body).jsonObject["ui"]?.jsonObject
                ?.get("action")?.jsonPrimitive?.content
                ?: return SettingsOutcome(HttpStatusCode.BadGateway.value, """{"error":"settings flow missing ui.action"}""")
            post(action, session, payload)
        } catch (e: Exception) {
            // Log the failure class ONLY — never the payload (which carries the new password / email).
            log.warn("settings submit failed (fail-closed): {}", e.javaClass.simpleName)
            SettingsOutcome(HttpStatusCode.BadGateway.value, """{"error":"settings flow unavailable"}""")
        }
    }

    private suspend fun get(url: String, session: SessionCredential): SettingsOutcome {
        val resp = client.get(url) {
            header("Accept", "application/json"); authHeader(session); timeout { requestTimeoutMillis = timeoutMs }
        }
        return SettingsOutcome(resp.status.value, resp.bodyAsText())
    }

    private suspend fun post(url: String, session: SessionCredential, payload: String): SettingsOutcome {
        val resp = client.post(url) {
            header("Accept", "application/json"); contentType(ContentType.Application.Json)
            authHeader(session); setBody(payload); timeout { requestTimeoutMillis = timeoutMs }
        }
        return SettingsOutcome(resp.status.value, resp.bodyAsText())
    }

    // Send ONLY the header matching the credential's source — both would poison the Kratos settings flow
    // exactly as it poisons whoami (the real-path bug).
    private fun io.ktor.client.request.HttpRequestBuilder.authHeader(session: SessionCredential) {
        when (session.source) {
            SessionCredential.Source.HEADER -> header("X-Session-Token", session.value)
            SessionCredential.Source.COOKIE -> header("Cookie", "$KRATOS_SESSION_COOKIE=${session.value}")
        }
    }
}
