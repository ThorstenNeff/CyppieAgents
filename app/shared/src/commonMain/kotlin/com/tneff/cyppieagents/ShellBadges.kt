package com.tneff.cyppieagents

import com.tneff.cyppieagents.agentview.AgentStatus
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.window.WindowBadge

/**
 * Pure derivation of the per-window activity badges (CYP-55) from the shell's already-collected,
 * honest state. Kept Compose-free and side-effect-free (like [com.tneff.cyppieagents.agentview.deriveStatus]
 * / the reducers) so the policy — fail-closed, focus-gated, threshold — is unit- and mutation-testable
 * without the UI. The shell collects the inputs from the always-alive VMs and renders the result.
 *
 * Three honest sources, one badge per window (the most relevant), per `docs/WINDOW-BADGES.md`:
 * - **B1** Comm activity: [commUnread] messages from others since the comm window was last focused
 *   → a [WindowBadge.Count]. The count is already reset/suppressed while focused (in the VM), so a
 *   positive value means "new since you last looked".
 * - **A1** Agent attention: an agent whose [AgentStatus] is the honest [AgentStatus.ERROR] (never a
 *   faked `WAITING_FOR_INPUT`) → a [WindowBadge.Attention].
 * - **C1** Event-Log severity: the highest [Severity] currently in the operator-gated tail buffer,
 *   but only when it reaches [Severity.WARN] (routine INFO/DEBUG traffic is not a hint) → a
 *   [WindowBadge.SeverityLevel]. [tailMaxSeverity] is `null` whenever there is no allowed source (no
 *   operator token → no tail window → no events), so the badge is **omitted**, never faked.
 *
 * **Fail-closed:** a window appears in the result only with an honest source. No source → no entry →
 * no `windowBadge.<id>` node downstream (absence is the empty state).
 *
 * **Focus-gate:** the badge is a hint for windows you are *not* looking at, so the focused window /
 * active pager page ([focusedId]) never carries one (WINDOW-BADGES §1.2/§4).
 */
fun deriveWindowBadges(
    focusedId: String?,
    commWindowId: String,
    commUnread: Int,
    agentStatuses: Map<String, AgentStatus>,
    eventTailWindowId: String,
    tailMaxSeverity: Severity?,
): Map<String, WindowBadge> = buildMap {
    // B1 — Comm activity count.
    if (focusedId != commWindowId && commUnread > 0) {
        put(commWindowId, WindowBadge.Count(commUnread))
    }
    // A1 — Agent ERROR (honest), one badge per erroring, non-focused agent.
    for ((id, status) in agentStatuses) {
        if (id != focusedId && status == AgentStatus.ERROR) {
            put(id, WindowBadge.Attention)
        }
    }
    // C1 — Event-Log max severity at/above WARN, on the (inherently operator-gated) tail window only.
    if (tailMaxSeverity != null && tailMaxSeverity >= Severity.WARN && focusedId != eventTailWindowId) {
        put(eventTailWindowId, WindowBadge.SeverityLevel(tailMaxSeverity))
    }
}
