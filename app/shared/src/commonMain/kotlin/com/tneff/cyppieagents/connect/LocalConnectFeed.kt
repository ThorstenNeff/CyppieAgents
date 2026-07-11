package com.tneff.cyppieagents.connect

import kotlinx.coroutines.flow.Flow

/**
 * CYP-419 (Epic CYP-395 S-L) — the **typed** local-connect cause (spec §6 / seam **S-2**). The four causes the
 * backend feed reports; [tagQualifier] is the camelCase segment for `hubConnect.state.error.<cause>`. **The client
 * NEVER guesses the cause** — a hedged, invented error text would be dishonest (H1/§13). The feed supplies it.
 */
enum class ConnectCause(val tagQualifier: String) {
    HUB_OFFLINE("hubOffline"),
    PORT_UNREACHABLE("portUnreachable"),
    HANDSHAKE_FAILED("handshakeFailed"),
    NEVER_ONLINE("neverOnline"),
}

/**
 * CYP-419 — the honest local-connect progress (spec §6). `attempting`/`handshake` are **in-progress** (rendered
 * neutral, never green, never a premature LIVE); `Connected` is the ONLY LIVE truth; `Failed` carries the typed
 * [ConnectCause]. Registry-presence (`HubDescriptor.online`) is a SEPARATE truth (H1) and never appears here.
 */
sealed interface ConnectProgress {
    data object Attempting : ConnectProgress
    data object Handshake : ConnectProgress
    data object Connected : ConnectProgress
    data class Failed(val cause: ConnectCause) : ConnectProgress
}

/**
 * CYP-419 (S-L) — the local-connect feed the §6 states render from. **Stub-first** ([StubLocalConnectFeed]); the
 * real feed (extending today's `ConnectionStatus{CONNECTING,LIVE,DISCONNECTED}` with `handshake` + typed causes)
 * is seam S-2, wired in S-J. Modelled as a cold [Flow] of [ConnectProgress] per connect attempt.
 */
interface LocalConnectFeed {
    /** Attempt a local connect to [hub] and emit the typed progress until LIVE or a typed failure. */
    fun connect(hub: HubDescriptor): Flow<ConnectProgress>
}
