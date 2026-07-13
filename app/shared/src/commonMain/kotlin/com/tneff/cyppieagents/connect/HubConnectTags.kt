package com.tneff.cyppieagents.connect

/**
 * CYP-395 hubConnect test-tag area — the frozen contract from `docs/design/hub-connection-tags.md @8265e4fc`,
 * coordinated with Tester/QA (CYP-7) via the PO (shared API — never rename silently). Prefixless, dotted
 * `<area>[.<scopeId>].<element>[.<qualifier>]`; segment values are `[A-Za-z0-9-]+` camelCase (no dots inside a value).
 *
 * The mode nodes landed in CYP-416 (S-M); the onboarding / credential / hub-list / connect-state nodes land in
 * CYP-419 (S-L). **One ratified change vs. the frozen doc (PO 2026-07-11): `ready.enter` → `ready.toWorkspace`**
 * (nav-consistency with the `to<Target>` convention). `hubRow`/`hubPresence` embed the **opaque CP `hubId`** (never
 * the editable display `name`); `stateError` embeds the backend-supplied camelCase cause.
 */
object HubConnectTags {

    // --- Onboarding / Erststart (Seq A) ---
    /** Host anchor of the onboarding stepper (step progress). */
    const val ONBOARDING_STEPPER = "hubConnect.onboarding.stepper"
    /** A0 hub-preparation / loading state. */
    const val PREPARE = "hubConnect.prepare"
    /** A2 editable hub-name field (hostname prefilled, Q1). */
    const val REGISTER_NAME = "hubConnect.register.name"
    /** A2 register action. */
    const val REGISTER_SUBMIT = "hubConnect.register.submit"
    /** A2 registration error (CP unreachable), errorContainer. */
    const val REGISTER_ERROR = "hubConnect.register.error"
    /** A4 "Loslegen" → hub workspace. (Frozen `ready.enter` renamed to `ready.toWorkspace` per PO nav-convention.) */
    const val READY_TO_WORKSPACE = "hubConnect.ready.toWorkspace"

    // --- Credentials (Seq A / §7) — mirrors the settings.apiKey.* pattern, own render → own tags ---
    /** Write-only credential field (PasswordVisualTransformation). */
    const val CREDS_INPUT = "hubConnect.creds.input"
    /** Reveal toggle (text label, no emoji) — unmasks only the current input. */
    const val CREDS_REVEAL = "hubConnect.creds.reveal"
    /** Read-only masked status line `***<last4>` (server-masked; never plaintext). */
    const val CREDS_MASKED = "hubConnect.creds.masked"
    /** Validation in-progress (neutral). */
    const val CREDS_VALIDATING = "hubConnect.creds.validating"
    /** INFO "valid — stored" (never success-green). */
    const val CREDS_VALIDATED = "hubConnect.creds.validated"
    /** Error "key invalid" (errorContainer). */
    const val CREDS_INVALID = "hubConnect.creds.invalid"
    /** WARN "couldn't verify (Anthropic down)" — distinct from [CREDS_INVALID] (H3). */
    const val CREDS_UNREACHABLE = "hubConnect.creds.unreachable"

    // --- Hub-Auswahl (Seq B) ---
    /** Container of the hub list. */
    const val HUBS_LIST = "hubConnect.hubs.list"
    /** Empty state — honest "no hubs yet" waiting copy (register is vestigial: hubs self-admit, CYP-530). */
    const val HUBS_EMPTY = "hubConnect.hubs.empty"
    /** CYP-530 Δ2: empty-state Refresh affordance (re-query the list for self-admitted hubs) — replaces the dead register CTA. */
    const val HUBS_REFRESH = "hubConnect.hubs.refresh"
    /** `GET /hubs` failed (CP unreachable), errorContainer (H5). */
    const val HUBS_ERROR = "hubConnect.hubs.error"

    /** One hub row. [hubId] = the **opaque CP id** (`[A-Za-z0-9-]+`), never the display name. */
    fun hubRow(hubId: String) = "hubConnect.hubs.row.$hubId"
    /** The row's **Registry-Presence** (advisory, H1) — dot + label, never success-green, never "connected". */
    fun hubPresence(hubId: String) = "hubConnect.hubs.row.$hubId.presence"

    // --- Modus-Wahl (Seq B / §5) — landed in CYP-416 (S-M) ---
    /** Local option — active, default focus. */
    const val MODE_LOCAL = "hubConnect.mode.local"
    /** Remote option — LIVE and selectable since CYP-471 (Noise-E2E via the Control Plane). */
    const val MODE_REMOTE = "hubConnect.mode.remote"
    /** Primary action "Verbinden" → routes to the selected mode's connect (§6 local / §7 remote). */
    const val MODE_CONNECT = "hubConnect.mode.connect"

    // --- Lokal-Connect-Zustände (§6) — inherits the ConnectionStatus idiom (neutral in-progress, never green) ---
    /** Connection attempt (neutral `onSurfaceVariant`, never green). */
    const val STATE_ATTEMPTING = "hubConnect.state.attempting"
    /** Handshake (neutral). */
    const val STATE_HANDSHAKE = "hubConnect.state.handshake"
    /** LIVE `●`+`primary` — appears **only** on real LIVE, never before. */
    const val STATE_CONNECTED = "hubConnect.state.connected"
    /** CYP-523: Seq-B forward action from the CONNECTED state → the workspace („Loslegen"). Additive (shared API w/ QA via PO). */
    const val STATE_TO_WORKSPACE = "hubConnect.state.toWorkspace"

    /**
     * Connect failure with a **typed** cause. [cause] ∈ `hubOffline` / `portUnreachable` / `handshakeFailed` /
     * `neverOnline` — supplied by the backend feed (seam S-2); the client **never guesses** it.
     */
    fun stateError(cause: String) = "hubConnect.state.error.$cause"
}
