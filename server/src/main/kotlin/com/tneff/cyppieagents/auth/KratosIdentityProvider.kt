package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * CYP-178 / P1 — the real [IdentityProvider]: validates a caller's Kratos session against
 * `GET {kratos-public}/sessions/whoami`, forwarding the credential as BOTH the `X-Session-Token` header
 * (native clients) and the `ory_kratos_session` cookie (browser) so Kratos accepts whichever is valid.
 * Maps the response to [ResolvedIdentity] (id + whether any verifiable address is verified).
 *
 * **RC1 fail-closed + bounded:** a non-200 (401 = invalid/expired), an inactive session, a missing id, a
 * malformed body, a **timeout** (bounded [timeoutMs]) or any connection error → **null** (unauthenticated).
 * A hung Kratos can never hang the guard, and an error never opens the gate.
 *
 * The register/recovery/login enumeration + timing safety is Kratos's (C1) — verified by the deploy-
 * coordinated live behavioral probe (RC2 option b), not this class.
 */
class KratosIdentityProvider(
    private val whoamiUrl: String,
    private val client: HttpClient = HttpClient(CIO) { install(HttpTimeout) },
    private val timeoutMs: Long = 3_000,
) : IdentityProvider {
    private val log = LoggerFactory.getLogger("auth.kratos")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(sessionCredential: String?): ResolvedIdentity? {
        if (sessionCredential.isNullOrBlank()) return null
        return try {
            val resp = client.get(whoamiUrl) {
                header("X-Session-Token", sessionCredential)
                header("Cookie", "$KRATOS_SESSION_COOKIE=$sessionCredential")
                timeout { requestTimeoutMillis = timeoutMs }
            }
            if (resp.status != HttpStatusCode.OK) return null // fail-closed: 401 = invalid/expired session
            val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            if (obj["active"]?.jsonPrimitive?.booleanOrNull != true) return null // inactive → unauthenticated
            val identity = obj["identity"]?.jsonObject ?: return null
            val id = identity["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val verified = identity["verifiable_addresses"]?.jsonArray?.any {
                it.jsonObject["verified"]?.jsonPrimitive?.booleanOrNull == true
            } ?: false
            ResolvedIdentity(id, verified)
        } catch (e: Exception) {
            log.warn("whoami failed (fail-closed → unauthenticated): {}", e.message)
            null // RC1: timeout / connection error / parse error → unauthenticated, never open
        }
    }
}
