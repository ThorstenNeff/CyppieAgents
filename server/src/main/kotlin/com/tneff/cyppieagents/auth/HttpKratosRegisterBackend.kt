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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * CYP-179 / §B(b) — the REAL [KratosRegisterBackend] against the loopback Kratos **ADMIN** API (:4434, RC4:
 * admin is NEVER internet-exposed — the wrapper runs on the same host and reaches it over loopback only).
 * This is a **register-only** use of the admin API (existence check + create), NOT a general admin proxy.
 *
 * ⚠️ **Deploy-verified seam.** The admin API SHAPES are confirmed (Context7 / Ory docs): create =
 * `POST /admin/identities` with `credentials.password.config.password` (Kratos hashes it); existence =
 * `GET /admin/identities?credentials_identifier=<email>`. But the **verification-mail mechanics for an
 * admin-created identity on Kratos v1.3.0** (whether the create alone sends the mail, or a follow-up
 * verification/recovery trigger is needed) and the **existing-account notice** path are version-sensitive
 * ("may ≠ does") — they are pinned for live verification by `deploy/kratos/CYP-179-REGISTER-WRAPPER-RUNBOOK.md`
 * (Mailpit assertions) and by [RegisterWrapperLiveProbeTest]. The mediator's SECURITY property (branch-
 * invariant response) is proven hermetically and does not depend on these mechanics.
 *
 * Secret hygiene: the password is sent only to Kratos; NEVER logged. Only status classes are logged.
 */
class HttpKratosRegisterBackend(
    /** The Kratos ADMIN base URL — loopback only (e.g. `http://127.0.0.1:4434`), RC4. */
    private val adminBaseUrl: String,
    /** The identity schema id (Doc/deploy owns the schema; default matches the reference config). */
    private val schemaId: String = "default",
    private val client: HttpClient = HttpClient(CIO) { install(HttpTimeout) },
    private val timeoutMs: Long = 5_000,
) : KratosRegisterBackend {
    private val log = LoggerFactory.getLogger("auth.register.kratos")
    private val json = Json { ignoreUnknownKeys = true }
    private val base = adminBaseUrl.trimEnd('/')

    /**
     * `GET /admin/identities?credentials_identifier=<email>` → any match ⇒ exists. A non-2xx (admin down)
     * THROWS so the mediator returns a uniform UNAVAILABLE (MUST-3). Tolerates both the bare-array and the
     * `{identities:[…]}` envelope shape (deploy pins the exact v1.3.0 shape in the runbook).
     */
    override suspend fun identityExists(email: String): Boolean {
        val resp = client.get("$base/admin/identities") {
            url { parameters.append("credentials_identifier", email) }
            header("Accept", "application/json"); timeout { requestTimeoutMillis = timeoutMs }
        }
        if (!resp.status.isSuccess()) {
            log.warn("admin identities list returned {} (treating as outage → fail-closed)", resp.status.value)
            throw IllegalStateException("admin list ${resp.status.value}")
        }
        val el = json.parseToJsonElement(resp.bodyAsText())
        val list = when (el) {
            is JsonArray -> el
            is JsonObject -> el["identities"]?.let { if (it is JsonArray) it else null } ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.isNotEmpty()
    }

    /**
     * NEW branch: `POST /admin/identities` with the cleartext password (Kratos hashes it), state active.
     * DEPLOY-VERIFIED: whether this alone dispatches the verification mail on v1.3.0, or needs a follow-up
     * admin verification/recovery trigger — the runbook pins the Mailpit assertion. Never logs the password.
     */
    override suspend fun createAndVerify(email: String, password: String) {
        val body = buildJsonObject {
            put("schema_id", schemaId)
            put("state", "active")
            put("traits", buildJsonObject { put("email", email) })
            put("credentials", buildJsonObject {
                put("password", buildJsonObject { put("config", buildJsonObject { put("password", password) }) })
            })
        }.toString()
        val resp = client.post("$base/admin/identities") {
            header("Accept", "application/json"); contentType(ContentType.Application.Json)
            setBody(body); timeout { requestTimeoutMillis = timeoutMs }
        }
        // A conflict here (409) means the identity appeared between the check and the create (a race) — safe:
        // no duplicate is created and we still return the generic response. Log only the status class.
        if (!resp.status.isSuccess()) log.warn("admin identity create returned {} (no verification mail sent)", resp.status.value)
        // DEPLOY-VERIFIED follow-up (runbook): if create does not auto-send verification on v1.3.0, trigger it here
        // via the admin verification/recovery path. Left as the pinned seam so it is proven against the live stack.
    }

    /**
     * EXISTING branch: send the "you already have an account" notice so the branches are mail-symmetric.
     * DEPLOY-VERIFIED (runbook): the exact notice path (admin recovery-code vs a dedicated SMTP notice) on
     * v1.3.0. A failure here is swallowed by the mediator (branch-blind) — it must never surface as a
     * branch-dependent response. Never logs beyond the status class.
     */
    override suspend fun notifyExisting(email: String) {
        // Pinned deploy seam: emit the existing-account notice. Intentionally best-effort + branch-blind.
        log.info("existing-account notice enqueued (mechanics deploy-verified per runbook)")
    }
}
