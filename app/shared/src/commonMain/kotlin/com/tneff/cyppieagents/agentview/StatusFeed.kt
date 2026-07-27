package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.net.isAccessRevoked
import com.tneff.cyppieagents.net.logWsError
import com.tneff.cyppieagents.net.readCloseCode
import com.tneff.cyppieagents.net.reconnecting
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile

/**
 * CYP-819 — the shared **1008-terminal contract** for the four read-only STATUS feeds
 * (`/ws/lifecycle`, `/ws/token-usage`, `/ws/busy-state`, `/ws/terminal-state`).
 *
 * Before this, each feed's source `.reconnecting()`-looped on ANY close, so a **1008 (VIOLATED_POLICY)**
 * auth-revoke re-dialed the **dead token forever** (the CYP-289 loop [com.tneff.cyppieagents.net.WsClose]
 * exists to prevent) and surfaced **no** revoke signal — the run-state / token / busy / terminal indicators
 * froze while still claiming their last value (the web-ts twin was CYP-815). These four were the only live
 * sources missing the terminal guard the sibling comm/acl/events feeds already have (each via its own
 * `AccessRevoked` sentinel). Centralising the close-code read + the terminal cut here means a fifth status
 * feed can't silently re-introduce the loop.
 */
sealed interface StatusFeedSignal<out T> {
    /** A decoded domain event from the feed. */
    data class Value<out T>(val value: T) : StatusFeedSignal<T>

    /**
     * The server closed with **1008 (VIOLATED_POLICY)** — a revoked/invalid token. TERMINAL: the feed must
     * NOT reconnect (re-opening re-sends the dead token) and the workspace surfaces the revoke.
     */
    data object Revoked : StatusFeedSignal<Nothing>
}

/**
 * Open [url] (token already in the query), stream each text frame through [decode], and end the emission
 * with a terminal [StatusFeedSignal.Revoked] **iff** the server closed with 1008. A normal/transient close
 * emits no sentinel → the caller's `.reconnecting()` re-subscribes and the feed recovers; a 1008 emits
 * `Revoked` so [terminalOnRevoke] cuts the reconnect loop.
 *
 * [readCloseCode] runs AFTER the frame loop under `NonCancellable` (Ktor cancels the frame channel while a
 * closing socket tears down, so a 1008 would otherwise be lost) — one place for the close-code contract,
 * mirroring [com.tneff.cyppieagents.eventlog.EventsWsClient].
 */
internal fun <T> HttpClient.statusFeed(
    url: String,
    label: String,
    decode: (String) -> T?,
): Flow<StatusFeedSignal<T>> = channelFlow {
    // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on Native),
    // so emitting from here is a cross-context send — exactly what channelFlow allows (the flow{} ISE behind
    // the CYP-115 Darwin churn).
    var closeCode: Short? = null
    try {
        webSocket(urlString = url) {
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        decode(frame.readText())?.let { this@channelFlow.send(StatusFeedSignal.Value(it)) }
                    }
                }
            } catch (e: CancellationException) {
                // Ktor tears a closing socket down by cancelling the frame channel. If a close reason is already
                // recorded, this IS that teardown (e.g. a 1008 reject) — not a real collector cancel — so swallow
                // it and report the code below. Otherwise it is a genuine cancellation and must propagate.
                if (!closeReason.isCompleted) throw e
            }
            closeCode = readCloseCode()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // CYP-125 parity: log a real socket failure (a normal close doesn't throw → no per-disconnect noise);
        // `.reconnecting()` still re-subscribes on completion/failure for a transient drop.
        logWsError(label, e)
    }
    // A 1008 revoke is terminal + distinct; a normal/transient close emits no sentinel (→ reconnect).
    if (isAccessRevoked(closeCode)) this@channelFlow.send(StatusFeedSignal.Revoked)
}

/**
 * The consumer half: `.reconnecting()` (transient recovery) but [onRevoked] fires and the stream **ends** on a
 * terminal [StatusFeedSignal.Revoked] (`takeWhile` cuts the loop → no dead-token hammer). Unwraps back to the
 * domain event type so the ViewModels are unchanged. Mirrors
 * [com.tneff.cyppieagents.workspace.LiveHubCapacitySource]'s `takeWhile { it !is AccessRevoked }`.
 */
internal fun <T> Flow<StatusFeedSignal<T>>.terminalOnRevoke(onRevoked: () -> Unit): Flow<T> =
    reconnecting()
        .onEach { if (it is StatusFeedSignal.Revoked) onRevoked() }
        .takeWhile { it !is StatusFeedSignal.Revoked }
        .map { (it as StatusFeedSignal.Value).value }
