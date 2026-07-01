package com.tneff.cyppieagents.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-183 / P4.3 — **S4 (delegation)**, the automated slice of the OIDC spike: initiate a native OIDC login
 * flow (`{method:oidc, provider:github}`) against live Kratos and assert Kratos redirects to GitHub's
 * authorize endpoint carrying **`state`** and a **`code_challenge`** (PKCE). This proves Kratos generates +
 * owns the OAuth security params (the platform re-implements none — the P4 minimal-boundary), and that the
 * P4.1 `github` provider config actually loaded.
 *
 * The HARD S1 linking-takeover check + S2/S3 need a real GitHub account → the manual/deploy runbook
 * (`deploy/kratos/P4-OIDC-SPIKE-RUNBOOK.md`); those are NOT automatable here.
 *
 * **RUN-gated + hermetic-safe:** no `KRATOS_PUBLIC_URL` → no-op green (CI never touches a live Kratos).
 *   `KRATOS_PUBLIC_URL=http://127.0.0.1:4433 ./gradlew :server:test --tests "*OidcSpikeTest" --rerun-tasks`
 */
class OidcSpikeTest {

    private val base: String? = System.getenv("KRATOS_PUBLIC_URL")?.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun githubOidc_flowDelegatesStateAndPkceToKratos() {
        val kratos = base ?: return // off-box → no-op green

        // 1. Create a native login flow, 2. submit the oidc method for github.
        val flow = get("$kratos/self-service/login/api")
        val action = json.parseToJsonElement(flow).jsonObject["ui"]?.jsonObject?.get("action")?.jsonPrimitive?.content
            ?: error("login flow missing ui.action")
        val resp = post(action, """{"method":"oidc","provider":"github"}""")

        // Kratos answers an API oidc submit with a browser-location-change to GitHub's authorize URL.
        val body = resp.body()
        val redirect = Regex("https://github\\.com/login/oauth/authorize[^\"\\s]*").find(body)?.value
            ?: error("no GitHub authorize redirect in the flow response (is the github provider loaded? status=${resp.statusCode()}) body=${body.take(400)}")

        // Delegation proof: Kratos generated the OAuth security params — the platform touches none of this.
        assertTrue(redirect.contains("state="), "Kratos must generate an OAuth `state` (CSRF) — not found in: $redirect")
        assertTrue(redirect.contains("code_challenge="), "Kratos must use PKCE (`code_challenge`) — not found in: $redirect")
        assertTrue(redirect.contains("client_id=Ov23lioeKkKuWAesQBKT"), "the redirect must carry our public client_id")
        println("RC2/P4 S4 delegation OK — Kratos → GitHub authorize with state+PKCE: ${redirect.take(120)}…")
    }

    private fun get(url: String): String = http.send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun post(url: String, jsonBody: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
