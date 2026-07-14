package com.tneff.cyppieagents.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing of the **minimal** slice of the Ory Kratos self-service wire shapes the client's login-gate
 * binding needs (CYP-182). `:app:shared` does **not** apply the kotlinx.serialization compiler plugin
 * (only `:core` does), so — exactly like the server's `KratosSettingsClient` — these navigate the JSON
 * via [JsonElement] rather than generated serializers. Version-tolerant: only the few fields the fixed
 * email/password mapping reads (PO Q3) are touched; every other Kratos field is ignored, so a Kratos
 * minor bump can't break the client. Never parses messages for enumeration cues.
 */
internal val KratosJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** A Kratos flow's identity + submit target (from `GET /self-service/{kind}/api`). */
internal data class KratosFlowRef(val id: String, val action: String)

/** Parse `{ id, ui: { action } }`; missing fields → blank (the caller falls back to a constructed URL). */
internal fun parseKratosFlow(body: String): KratosFlowRef = runCatching {
    val root = KratosJson.parseToJsonElement(body).jsonObject
    val id = root["id"]?.jsonPrimitive?.content ?: ""
    val action = root["ui"]?.jsonObject?.get("action")?.jsonPrimitive?.content ?: ""
    KratosFlowRef(id, action)
}.getOrElse { KratosFlowRef("", "") }

/** The native `session_token` from a successful login/registration body, or null (browser sets a cookie). */
internal fun parseKratosSessionToken(body: String): String? = runCatching {
    KratosJson.parseToJsonElement(body).jsonObject["session_token"]?.jsonPrimitive?.content
}.getOrNull()?.ifBlank { null }

/** The caller's OWN `identity.traits.email` from `sessions/whoami` (self-reflecting), or null. */
internal fun parseKratosWhoamiEmail(body: String): String? = runCatching {
    KratosJson.parseToJsonElement(body).jsonObject["identity"]?.jsonObject
        ?.get("traits")?.jsonObject?.get("email")?.jsonPrimitive?.content
}.getOrNull()?.ifBlank { null }

/**
 * The Kratos error id (`error.id`, or a top-level `id` fallback), or null. Used to recognise the browser-flow
 * `browser_location_change_required` — a **success** signal ("code accepted, continue"), not a failure — so
 * the recovery/settings completion isn't misread as an invalid token.
 */
internal fun parseKratosErrorId(body: String): String? = runCatching {
    val root = KratosJson.parseToJsonElement(body).jsonObject
    root["error"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        ?: root["id"]?.jsonPrimitive?.content
}.getOrNull()

/**
 * CYP-576 — the native API-flow `session_token_exchange_code` (the "init half", ~64ch) from the `GET
 * /self-service/login/api?return_session_token_exchange_code=true` response. Redeemed together with the loopback
 * `return_to_code` at `GET /sessions/token-exchange` for a native `session_token` (no browser-cookie handoff).
 * Version-verified field name (Backend live-staging spike 2026-07-14). Null when absent (flow not exchange-armed).
 */
internal fun parseKratosExchangeInitCode(body: String): String? = runCatching {
    KratosJson.parseToJsonElement(body).jsonObject["session_token_exchange_code"]?.jsonPrimitive?.content
}.getOrNull()?.ifBlank { null }

/** The `redirect_browser_to` URL from a Kratos browser-location-change response (the OIDC → GitHub URL), or null. */
internal fun parseKratosRedirectUrl(body: String): String? = runCatching {
    KratosJson.parseToJsonElement(body).jsonObject["redirect_browser_to"]?.jsonPrimitive?.content
}.getOrNull()?.ifBlank { null }

/** The `csrf_token` hidden-node value from a **browser** flow's `ui.nodes` (required to submit it), or null. */
internal fun parseKratosCsrfToken(body: String): String? = runCatching {
    KratosJson.parseToJsonElement(body).jsonObject["ui"]?.jsonObject?.get("nodes")?.jsonArray
        ?.firstOrNull { n -> n.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "csrf_token" }
        ?.jsonObject?.get("attributes")?.jsonObject?.get("value")?.jsonPrimitive?.content
}.getOrNull()?.ifBlank { null }
