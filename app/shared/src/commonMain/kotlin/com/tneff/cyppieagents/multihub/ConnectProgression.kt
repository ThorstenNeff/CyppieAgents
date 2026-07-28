package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState

/**
 * CYP-866 (Compose-M5-Mirror of web-ts CYP-855) — the connect-progression **mapping** for the switcher-host (M4):
 * given the ACTIVE hub's connection state, which in-flight progression step (if any) to render. ONE connection at a
 * time (switch-first) → not per-hubId. It resolves to a transient [ProgressionState] or **null** when the connect is
 * NOT an in-flight progression (a terminal/ended state the Failure-Region CYP-823 owns, never the progression chrome).
 *
 * The Compose twin of web-ts `progressionStateFor`. web-ts renders five states; Compose renders **six** —
 * [ProgressionState.AUTHENTICATING] is intentionally COMPOSE-ONLY (CYP-827 6th state: native separates hub-identity
 * from operator-authority over the noise tunnel; web relocates operator auth into the WSS handshake so it has no
 * distinct step). This mapping is display-only: it reads a STATE enum and returns a STATE enum — arming DARK (no dial).
 */
enum class ProgressionState { DIALING, HANDSHAKE, TRUST_CHECK, AUTHENTICATING, RECONNECTING, CONNECTED }

/**
 * Map the active [RemoteConnState] to the progression step to render, or **null** when it is NOT an in-flight
 * progression: [RemoteConnState.LOST] is terminal/ended → the Failure-Region (CYP-823) owns it, never this chrome.
 *
 * **Exhaustive `when` (no `else`) — the assertNever twin:** a new [RemoteConnState] value fails to compile HERE until
 * it is routed, so a phase can never silently fall through to a wrong/absent render.
 */
fun progressionStateFor(conn: RemoteConnState): ProgressionState? = when (conn) {
    RemoteConnState.RELAY_DIALING -> ProgressionState.DIALING
    RemoteConnState.E2E_HANDSHAKE -> ProgressionState.HANDSHAKE
    RemoteConnState.TRUST_CHECK -> ProgressionState.TRUST_CHECK
    RemoteConnState.AUTHENTICATING -> ProgressionState.AUTHENTICATING
    RemoteConnState.RECONNECTING -> ProgressionState.RECONNECTING // honestly-uncertain + retryable, NEVER terminal
    RemoteConnState.CONNECTED -> ProgressionState.CONNECTED
    RemoteConnState.LOST -> null // terminal/ended → the Failure-Region (CYP-823), not an in-flight progression step
}
