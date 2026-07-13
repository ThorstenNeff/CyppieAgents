package com.tneff.cyppieagents.controlplane

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * CYP-526 — **shared boot-window CP-HTTP resilience**, single-sourced across the two hub→CP calls that both dial the
 * hub's OWN edge (`CYPPIE_CP_URL` → Caddy → 127.0.0.1:<port>) and therefore share the CYP-524 race class: the
 * admission round-trip ([HubAdmissionClient], CYP-524) and the rendezvous register ([HubRendezvousRegistrar], CYP-526).
 * "Derive once" (CLAUDE.md): the retry policy + the JSON-body guard live here so admit and register cannot drift.
 */

/** A connect/IO error at the transport (edge not up, DNS, reset) is transient in the boot window → retryable. */
suspend inline fun cpRequest(stage: String, call: () -> HttpResponse): HttpResponse =
    try {
        call()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        throw AdmissionTransientException("CP $stage transport error: ${e.message}", e)
    }

/**
 * Fail-closed body guard: only a 2xx `application/json` is a real CP payload. Anything else is read as a BOUNDED
 * diagnostic prefix (these bodies carry no secret — a public nonce / typed result / rendezvous binding; a 502 or HTML
 * warmup prefix is exactly what we want visible) and thrown as **transient** (retry) or **terminal** (fail fast) —
 * never a blind `.body<T>()` that turns a non-JSON edge response into an opaque `SourceByteReadChannel`.
 */
suspend fun cpJsonGuard(resp: HttpResponse, stage: String) {
    val ct = resp.contentType()
    val isJson = ct?.match(ContentType.Application.Json) == true
    if (resp.status.isSuccess() && isJson) return
    val prefix = runCatching { resp.bodyAsText() }.getOrNull()?.take(CP_BODY_PREFIX)?.replace('\n', ' ')?.trim()
    val msg = "CP $stage → HTTP ${resp.status.value}, content-type ${ct ?: "none"}${prefix?.let { " — $it" } ?: ""}"
    // A 4xx the CP itself served (auth) will NOT self-heal → terminal. A 5xx, a transport-level non-JSON body, or a
    // 2xx that isn't JSON (an edge warmup/SPA page) is transient in the boot window → retry.
    val terminal = resp.status.value in 400..499 && isJson
    if (terminal) throw AdmissionRejectedException(msg) else throw AdmissionTransientException(msg)
}

/**
 * Bounded retry over [attempt]: retries on [AdmissionTransientException] up to [retry].maxAttempts with the policy's
 * exponential backoff, then returns [onExhausted]. A terminal [AdmissionRejectedException] (a CP-served 4xx) is NOT
 * caught here → it propagates immediately (auth never self-heals). Bounded — never an unbounded loop.
 */
suspend fun <T> cpRetry(
    retry: AdmissionRetry,
    sleep: suspend (Long) -> Unit,
    onExhausted: (AdmissionTransientException) -> T,
    attempt: suspend () -> T,
): T {
    var n = 0
    while (true) {
        n++
        try {
            return attempt()
        } catch (t: AdmissionTransientException) {
            if (n >= retry.maxAttempts) return onExhausted(t)
            sleep(retry.delayForAttempt(n))
        }
    }
}

const val CP_BODY_PREFIX = 200
