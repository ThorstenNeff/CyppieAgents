package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.model.WireFrame
import kotlinx.coroutines.flow.Flow

/**
 * CYP-142 (S4.1) — the bridge's view of its `/ws/hub` connection: serialized sends + an inbound frame
 * stream. Abstracted so the relay logic is hermetically testable (a fake link) and the transport (a Ktor
 * WS client authenticated with the agent's S3 token) is swappable (S4.3 packaging).
 *
 * [send] MUST be safe for concurrent callers — the relay sends `WireHello`/`WireSubscribe` and outbound
 * `WireSend`s possibly concurrently, and (like the server's RC1) two unsynchronized writers on one socket
 * would interleave frames. The impl serializes; the `WireEnvelope{v=1}` wrapping is the impl's concern.
 */
interface WireLink {
    suspend fun send(frame: WireFrame)
    val incoming: Flow<WireFrame>
    suspend fun close()
}
