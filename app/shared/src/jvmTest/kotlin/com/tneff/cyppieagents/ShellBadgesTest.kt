package com.tneff.cyppieagents

import com.tneff.cyppieagents.agentview.AgentStatus
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.window.WindowBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-55: the badge **policy** ([deriveWindowBadges]) is pure, so fail-closed, focus-gate and the B1/A1/C1
 * thresholds are mutation-provable without the UI. Every guard below turns the protected assertion RED
 * when reverted (no vacuum tests):
 * - fail-closed: no honest source → empty map (no `windowBadge.<id>` downstream).
 * - focus-gate: the focused window/active page never carries a badge.
 * - A1: only honest `ERROR` (never a faked `WAITING_FOR_INPUT`).
 * - C1: only at/above `WARN` (routine INFO/DEBUG is not a hint), and only with an allowed source
 *   (`tailMaxSeverity == null` ⇒ omitted — the operator gate, expressed as absence).
 */
class ShellBadgesTest {

    private fun derive(
        focusedId: String? = "acl",
        commUnread: Int = 0,
        agentStatuses: Map<String, AgentStatus> = emptyMap(),
        tailMaxSeverity: Severity? = null,
    ): Map<String, WindowBadge> = deriveWindowBadges(
        focusedId = focusedId,
        commWindowId = "comm",
        commUnread = commUnread,
        agentStatuses = agentStatuses,
        eventTailWindowId = "eventtail",
        tailMaxSeverity = tailMaxSeverity,
    )

    @Test
    fun noSources_failClosed_emptyMap() {
        // The fail-closed absence anchor: nothing honest → nothing rendered.
        assertTrue(derive().isEmpty())
    }

    // --- B1: Comm unread ---------------------------------------------------------------------------

    @Test
    fun b1_unreadWhileUnfocused_countBadge() {
        assertEquals(WindowBadge.Count(3), derive(focusedId = "acl", commUnread = 3)["comm"])
    }

    @Test
    fun b1_focusGate_noBadgeWhenCommFocused() {
        assertNull(derive(focusedId = "comm", commUnread = 3)["comm"])
    }

    @Test
    fun b1_zeroUnread_noBadge() {
        assertNull(derive(commUnread = 0)["comm"])
    }

    // --- A1: Agent ERROR ---------------------------------------------------------------------------

    @Test
    fun a1_agentError_attentionBadge() {
        assertEquals(WindowBadge.Attention, derive(agentStatuses = mapOf("frontend" to AgentStatus.ERROR))["frontend"])
    }

    @Test
    fun a1_onlyError_noWaitingForInputFake() {
        // Every non-ERROR status — including WAITING_FOR_INPUT — must NOT raise a badge (no fake).
        val r = derive(
            agentStatuses = mapOf(
                "po" to AgentStatus.RUNNING,
                "frontend" to AgentStatus.IDLE,
                "backend" to AgentStatus.WAITING_FOR_INPUT,
            ),
        )
        assertTrue(r.isEmpty(), "non-ERROR statuses must not badge — got $r")
    }

    @Test
    fun a1_focusGate_noBadgeWhenAgentFocused() {
        assertNull(derive(focusedId = "frontend", agentStatuses = mapOf("frontend" to AgentStatus.ERROR))["frontend"])
    }

    // --- C1: Event-Log severity (gated window) -----------------------------------------------------

    @Test
    fun c1_warnAndError_severityBadge() {
        assertEquals(WindowBadge.SeverityLevel(Severity.WARN), derive(tailMaxSeverity = Severity.WARN)["eventtail"])
        assertEquals(WindowBadge.SeverityLevel(Severity.ERROR), derive(tailMaxSeverity = Severity.ERROR)["eventtail"])
    }

    @Test
    fun c1_belowThreshold_noBadge() {
        // INFO/DEBUG are routine traffic, not a hint — the threshold guard must drop them.
        assertNull(derive(tailMaxSeverity = Severity.INFO)["eventtail"])
        assertNull(derive(tailMaxSeverity = Severity.DEBUG)["eventtail"])
    }

    @Test
    fun c1_noAllowedSource_failClosed_noBadge() {
        // `null` models "no operator token → no tail window → no events" — omission, never a fake.
        assertNull(derive(tailMaxSeverity = null)["eventtail"])
    }

    @Test
    fun c1_focusGate_noBadgeWhenTailFocused() {
        assertNull(derive(focusedId = "eventtail", tailMaxSeverity = Severity.ERROR)["eventtail"])
    }
}
