package com.tneff.cyppieagents.workspace

/**
 * `testTag` contract for the Multi-User MEMBER/OPERATOR surfaces (CYP-80 / CYP-186), exactly per
 * `docs/design/member-operator-tags.md`. Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>`,
 * segment values `[A-Za-z0-9-]+` (camelCase, no dots). **Area `workspace` is new** — 0 collision against the
 * existing `*Tags.kt`. **Shared API with QA (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * The **User-Tier** (human: operator/member) — NOT the Agent-Role (`agent_role_*`), which is untouched. Most
 * tier-dependent controls need NO new tag (only the gating boolean changes, not the node — they reuse the
 * existing GATED/read-only tags); new is only the role surface below. The roster ([MEMBERS] + [member]) is
 * structurally omitted for a MEMBER — a MEMBER's test tree never contains those nodes (enumeration seam §3.3).
 */
object WorkspaceTags {
    const val AREA = "workspace"

    /** Persistent role indicator in the top bar ("You are the operator / a member"). Visible to all. */
    const val ROLE_INDICATOR = "workspace.roleIndicator"

    /** The operator identity shown to a MEMBER ("Operator: %1$s"). Visible to a MEMBER only. */
    const val OPERATOR_NAME = "workspace.operatorName"

    /** Members roster container — mounted for an OPERATOR only (structurally omitted for a MEMBER). */
    const val MEMBERS = "workspace.members"

    /** Roster row for a user (scope = user id). Operator-only. */
    fun member(id: String) = "workspace.member.$id"

    /** The row's tier label (Operator/Member); `.you` qualifier marks the own entry. Operator-only. */
    fun memberRole(id: String) = "workspace.member.$id.role"

    // --- CYP-417 (S-G ResourceGovernor UI) — hub capacity + overload, workspace-scoped (ProjectSwitcherBar) ---

    /** Hub capacity readout "N/M agents". **Present ⇔ capacity estimated**; unknown ⇒ **absent** (null≠0, Q3). */
    const val CAPACITY = "workspace.capacity"

    /** The **full**-state qualifier (`current == estimatedMax`, Q2) — the readout tones WARN-amber. */
    const val CAPACITY_FULL = "workspace.capacity.full"

    /** The hub-scoped WARN overload banner — appears **only** on a real server-side fail-closed reject (H5). */
    const val OVERLOAD_BANNER = "workspace.overloadBanner"

    /** The banner's dismiss action ("Got it"); persistent-until-clears **and** dismissable (Q5). */
    const val OVERLOAD_BANNER_DISMISS = "workspace.overloadBanner.dismiss"

    /**
     * CYP-527 — the persistent full-width **remote-operating context** WARN banner. Present iff the workspace is
     * operating a hub over the REMOTE (Noise-E2E) transport AND that session is CONNECTED (bound to
     * `RemoteSessionState.conn == CONNECTED`, NOT `RemoteHubConnectGate.entered` which also fires locally). Distinct
     * from [ROLE_INDICATOR] (WHO you are, neutral, always) — this is WHERE the hub is (WARN, iff remote). UIUX-locked.
     */
    const val REMOTE_CONTEXT = "workspace.remoteContext"

    /**
     * CYP-427/M2 (Seam #1) — the E2E-pinned indicator in the affirmative remote-context banner. Present ⇔ identity is
     * REALLY pinned (a `HubTrust` fingerprint pin, NOT provisional trust); copy = `remote_connect_trust_pinned`. A
     * sub-node of [REMOTE_CONTEXT] so QA can tell "pinned is there" from "banner is there" (G2). Neutral/primary tone,
     * NEVER green (transport encryption + identity pin are two facts, neither a success). Frozen with QA/DS via the PO.
     */
    const val REMOTE_CONTEXT_PINNED = "workspace.remoteContext.pinned"

    /**
     * CYP-427/M2 (Seam #2) — the ONE global in-operation relay-drop surface (reconnect banner). Present ⇔
     * `RemoteSessionState.conn == RECONNECTING`; WARN-amber `▲`, NEVER red (the session is reconnecting, not dead;
     * terminal loss = `LOST` = a different path). Copy = `remote_connect_relay_dropped`. Distinct from
     * `RemoteConnectTags.RELAY_DROP` (the connect-flow row — a different surface). Frozen with QA/DS via the PO.
     */
    const val RELAY_DROP = "workspace.relayDrop"

    /**
     * CYP-427/M2 (Seam #2, H4) — in-flight actions honestly UNCERTAIN on a drop (never silently done). Present ⇔
     * `RemoteSessionState.inFlightUncertain == true`; a sub-node of [RELAY_DROP], WARN-amber. Copy =
     * `workspace_relay_uncertain`. Frozen with QA/DS via the PO.
     */
    const val RELAY_DROP_UNCERTAIN = "workspace.relayDrop.uncertain"

    // --- CYP-629 Inc4 (first-run skip → honest degraded workspace, ux-spec §6.2/§6.3a) ---

    /**
     * The persistent full-width **unconfigured** banner shown after a skip while the hub is still unconfigured
     * (key missing and/or repo not `CLONED_OK`). `INFO`/Polite — the degraded boot is **expected**, never alarm-red.
     * Specific, not generic: names what's open via the reused first-run step labels; a `CLONE_FAILED` repo carries the
     * clone-error copy, NOT "repository missing". Carries the collapse control + the resume CTA.
     */
    const val UNCONFIGURED_BANNER = "workspace.unconfiguredBanner"

    /** The banner's collapse control (§6.3a Nag-fix) → collapses to the passive [UNCONFIGURED_CHIP]. */
    const val UNCONFIGURED_COLLAPSE = "workspace.unconfiguredCollapse"

    /**
     * The collapsed, **passive** indicator chip ("Not configured"). Never wholly hidden (the unfinished state is a real
     * standing fact → hiding it would be an honesty omission), but passive: it carries **no** liveRegion — the collapsed
     * chip does not announce, so honesty is kept without an assistive back-door nag. Tap → re-expands the banner on demand.
     */
    const val UNCONFIGURED_CHIP = "workspace.unconfiguredChip"

    /** The banner's "Continue setup" CTA → reopens the FirstRunGate at the first open step (not always step 1). */
    const val SETUP_RESUME = "workspace.setupResume"
}
