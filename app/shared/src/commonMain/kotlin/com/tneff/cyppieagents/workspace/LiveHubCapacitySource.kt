package com.tneff.cyppieagents.workspace

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventsWsClient
import com.tneff.cyppieagents.model.Capacity
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.reconnecting
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * CYP-417 (Epic CYP-395 S-G) — the **live** [HubCapacitySource], the drop-in for [StubHubCapacitySource]
 * once Backend's `ResourceGovernor` seam is on develop. Both feeds are **server-authoritative** (the same
 * counts `admitSpawn` gates on, H5) — the pill never derives a count client-side:
 *  - **[capacity]** = the mount-time **`GET /api/capacity`** snapshot (MEMBER-tier, the `:core` [Capacity] DTO)
 *    followed by live **`CAPACITY_CHANGED`** events off the reused `/ws/events` feed. A failed/unauthorized
 *    snapshot ⇒ `null` ⇒ the readout is **absent** (H1/Q3 `null≠0`, never a fabricated "0/0").
 *  - **[rejections]** = one emission per **`SPAWN_REJECTED`** event (the real fail-closed reject, H2/H5).
 *
 * **Auth asymmetry (flagged to PO):** `GET /api/capacity` is MEMBER-tier but `/ws/events` is operator-only
 * (1008 → [EventLiveEvent.AccessRevoked]). The workspace is operator-context today (windows exist only when
 * `operatorToken != null`), and every sibling live source ([com.tneff.cyppieagents.agentview.BusyStateLiveSource]
 * etc.) binds the same `operatorToken` — so this does too. For a future pure-member the snapshot would show but
 * live updates would not; the pill then holds the last-known count (stale, never a lie).
 *
 * **AccessRevoked is terminal** (the [EventTailViewModel] anti-hammer lesson): a 1008 revoke won't clear
 * without re-auth, so `takeWhile { it !is AccessRevoked }` ends the chain rather than letting `.reconnecting()`
 * re-open `/ws/events` every backoff with the revoked token. A transient drop (source completes without a
 * revoke) still reconnects and the pill recovers.
 */
class LiveHubCapacitySource(
    private val events: EventLiveSource,
    private val snapshot: suspend () -> HubCapacity?,
    private val backoff: Backoff = Backoff(),
) : HubCapacitySource {

    /** The AgentShell one-liner: build both feeds from the shared transport (operator-token-bound, sibling-consistent). */
    constructor(client: HttpClient, httpBaseUrl: String, wsBaseUrl: String, token: String) : this(
        events = EventsWsClient(client, wsBaseUrl, token),
        snapshot = { fetchCapacity(client, httpBaseUrl, token) },
    )

    override fun capacity(): Flow<HubCapacity?> = flow {
        // Snapshot first so the pill is honest from mount (before the first spawn/exit moves capacity.changed).
        emit(snapshot())
        events.events(EventFilter(type = EventType.CAPACITY_CHANGED))
            .reconnecting(backoff)
            .takeWhile { it !is EventLiveEvent.AccessRevoked } // 1008 is terminal — no reconnect hammer
            .collect { ev -> if (ev is EventLiveEvent.Received) decode(ev.event)?.let { emit(it) } }
    }

    override fun rejections(): Flow<Unit> =
        events.events(EventFilter(type = EventType.SPAWN_REJECTED))
            .reconnecting(backoff)
            .takeWhile { it !is EventLiveEvent.AccessRevoked }
            .filterIsInstance<EventLiveEvent.Received>()
            .map { } // content-free: only the reject signal, never the event's detail (H6)

    /** Decode the content-free `{current, estimatedMax?}` counters from a capacity.changed event; absent current ⇒ null. */
    private fun decode(e: Event): HubCapacity? {
        val current = e.detail["current"]?.jsonPrimitive?.intOrNull ?: return null
        val max = e.detail["estimatedMax"]?.jsonPrimitive?.intOrNull // absent ⇒ null ⇒ the "N aktiv" no-max readout
        return HubCapacity(current, max)
    }

    companion object {
        /** `GET /api/capacity` (MEMBER-tier, mirrors [com.tneff.cyppieagents.eventlog.EventsApiClient]); non-2xx/throw ⇒ null (absent, H1). */
        private suspend fun fetchCapacity(client: HttpClient, baseUrl: String, token: String): HubCapacity? {
            val response = client.get("$baseUrl/api/capacity") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            val cap = CommJson.decodeFromString(Capacity.serializer(), response.bodyAsText())
            return HubCapacity(cap.current, cap.estimatedMax)
        }
    }
}
