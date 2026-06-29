package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

/** Thrown when an event-log REST call returns non-2xx (e.g. 401/403 from operator-gating, §7). */
class EventsHttpException(val status: Int, val bodyText: String) : Exception("events http $status")

/**
 * Ktor REST client for `/api/events` (CYP-39). Builds the **query string** from [EventFilter] + [Page]
 * — REST reads the filters from query params, NOT a JSON body (owner-confirmed) — and decodes the
 * `:core` [EventPage] via the shared [CommJson]. Operator-token bearer (operator-only, fail-closed §7),
 * exactly like `CommRepository`. Drop-in for [StubEventsApi]: the live swap in `AgentShell` is one default.
 *
 * Param encoding (reconcile final form with the CYP-39 server): `type` = the dotted `EventType.wire`,
 * `severity` = the lowercase `@SerialName` (`debug`/`info`/`warn`/`error`); time window half-open
 * `[since, until)`; `afterSeq` = the seq cursor; `limit` always sent.
 */
class EventsApiClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : EventsApi {

    override suspend fun query(filter: EventFilter, page: Page): EventPage {
        val params = buildList {
            filter.agentId?.let { add("agentId" to it) }
            filter.type?.let { add("type" to it.wire) }
            filter.severity?.let { add("severity" to it.name.lowercase()) }
            filter.since?.let { add("since" to it.toString()) }
            filter.until?.let { add("until" to it.toString()) }
            filter.correlationId?.let { add("correlationId" to it) }
            filter.sessionId?.let { add("sessionId" to it) }
            // CYP-94: the cross-project lens is sent ONLY when set (operator-gated path); no param → the
            // server keeps the forced-active default (CYP-102 not weakened). Wire contract: `projectId`
            // param, `all` sentinel = the operator's authorized projects (server validates).
            filter.projectId?.let { add("projectId" to it) }
            page.afterSeq?.let { add("afterSeq" to it.toString()) }
            add("limit" to page.limit.toString())
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val response = client.get("$baseUrl/api/events?$query") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw EventsHttpException(response.status.value, text)
        return CommJson.decodeFromString(EventPage.serializer(), text)
    }
}
