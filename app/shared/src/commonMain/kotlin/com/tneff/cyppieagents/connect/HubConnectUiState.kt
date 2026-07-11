package com.tneff.cyppieagents.connect

/**
 * CYP-419 (S-L) — the hubConnect flow state (spec §4–§6). Seq A (first-start register) and Seq B (hub selection +
 * connect) share this one machine; the entry [start][HubConnectViewModel.start] routes by whether the hub list is
 * empty. `connected` is expressed only through [Connecting] carrying [ConnectProgress.Connected] — there is no
 * "connected" state that can be reached before the feed reports real LIVE (H1/§13).
 */
sealed interface HubConnectUiState {
    /** A0 — hub preparation / initial load (system-side keygen), before the list is known. */
    data object Preparing : HubConnectUiState

    /** Fetching `GET /hubs`. */
    data object LoadingHubs : HubConnectUiState

    /** H5 — the CP is unreachable, so neither the hub list nor the first sign-in can proceed: an HONEST error. */
    data object HubsUnreachable : HubConnectUiState

    /** A2 — register this hub (editable [name], hostname-prefilled). */
    data class Register(val name: String, val phase: RegisterPhase) : HubConnectUiState

    /** A3 — Anthropic credentials. [masked] = the stored `***<last4>` (hinterlegt), [phase] = the validation truth. */
    data class Credentials(val masked: String?, val phase: CredentialPhase) : HubConnectUiState

    /** A4 — the hub is ready; the enter-workspace step. */
    data object Ready : HubConnectUiState

    /** B2 — the hub-selection list. Presence lives on each [HubDescriptor] (advisory, H1). */
    data class HubList(val hubs: List<HubDescriptor>) : HubConnectUiState

    /** B3 — mode choice for the picked [hub] (Local actionable, Remote disabled via `HubConnectModeChooser`). */
    data class ChoosingMode(val hub: HubDescriptor) : HubConnectUiState

    /** §6 — the honest local-connect progress for [hub]. [progress] is the feed's truth (cause never guessed). */
    data class Connecting(val hub: HubDescriptor, val progress: ConnectProgress) : HubConnectUiState
}

/** A2 registration sub-phase. */
enum class RegisterPhase { EDITING, REGISTERING, ERROR }

/** A3 credential sub-phase — the §7 tri-state (`UNREACHABLE` ≠ `INVALID`, H3). */
enum class CredentialPhase { ENTERING, VALIDATING, VALIDATED, INVALID, UNREACHABLE }
