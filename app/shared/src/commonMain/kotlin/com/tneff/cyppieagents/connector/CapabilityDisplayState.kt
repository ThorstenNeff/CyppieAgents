package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.agentview.AgentLifecycleState
import com.tneff.cyppieagents.model.Capabilities

/**
 * CYP-746 (UIUX capability-state UX, follow-up to CYP-742) — the **five** display states of an agent's connector
 * fidelity, all **client-derivable** from facts the window already carries (caps + loading + [AgentLifecycleState]),
 * **no backend seam**. Modelled as a state (not a bare nullable field) so a hung load can never masquerade as full
 * fidelity and "unknown" is never confused with "not started".
 *
 *  - [FULL]        — caps present, not degraded → **no badge** (fail-closed by absence, the CYP-280 discipline).
 *  - [RESTRICTED]  — caps present, degraded → the `!` "Eingeschränkt" badge.
 *  - [LOADING]     — the caps read is in flight (C) → no badge, but **time-bounded** (see [capabilityDisplayState]).
 *  - [UNKNOWN]     — caps `null` while the agent is **RUNNING** (D) → `○` "not yet reported" (GATED): it SHOULD have
 *                    reported and hasn't — an honest concern, not a settled absence.
 *  - [NOT_STARTED] — caps `null` while the agent is **not RUNNING** (E) → no fidelity badge; the **lifecycle dot**
 *                    carries the distinction (`statusDotSpec` RING/FILL — "unknown is a different axis"), so D vs E
 *                    needs no new capability glyph (`·` vs `○` don't read at badge size).
 *
 * **Honest boundary (UIUX):** "not started" is NOT distinguishable from "ran but never reported" (both are
 * not-RUNNING + `null`) — the copy claims only "isn't running / didn't report at start", true in both subcases; no
 * invented `NEVER_STARTED`.
 */
enum class CapabilityDisplayState { FULL, RESTRICTED, LOADING, UNKNOWN, NOT_STARTED }

/**
 * CYP-746 — derive the [CapabilityDisplayState] from the facts the window holds. [loadingTimedOut] is the C→D
 * fallback: once a load persists past the threshold it is no longer treated as in-flight, so a **hung** load falls
 * through to UNKNOWN/NOT_STARTED (honest) instead of hanging silently as "loading" (or, worse, ever looking full).
 *
 * Precedence is deliberate and fail-closed: a present-caps verdict (FULL/RESTRICTED) wins; else an in-flight load
 * (not yet timed out) is LOADING; else the lifecycle axis splits the honest absence into UNKNOWN (running) vs
 * NOT_STARTED (not running). `caps == null` therefore can NEVER yield FULL/RESTRICTED — no dimension is ever
 * claimed "available" without caps.
 */
fun capabilityDisplayState(
    caps: Capabilities?,
    loading: Boolean,
    loadingTimedOut: Boolean,
    lifecycle: AgentLifecycleState,
): CapabilityDisplayState = when {
    caps != null && !caps.isDegraded -> CapabilityDisplayState.FULL
    caps != null -> CapabilityDisplayState.RESTRICTED
    loading && !loadingTimedOut -> CapabilityDisplayState.LOADING
    lifecycle == AgentLifecycleState.RUNNING -> CapabilityDisplayState.UNKNOWN
    else -> CapabilityDisplayState.NOT_STARTED
}
