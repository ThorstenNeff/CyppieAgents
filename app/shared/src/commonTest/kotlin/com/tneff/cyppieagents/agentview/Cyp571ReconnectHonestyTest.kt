package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.deriveWindowBadges
import com.tneff.cyppieagents.window.WindowBadge
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-571 (reconnect-honesty verify, HEADLESS V4/V5) — locks the **distributed** "no-stale-as-live" honesty of the
 * shipped CYP-198/CYP-204 reconnect behavior at the pure-derivation layer (the render surfaces bind these: the
 * `windowBadge.<id>` badge + the transcript). No design here — verifies TODAY's behavior is honest.
 *
 *  - **V5 [deriveStatus]:** a fully-REPLAYED transcript ending on a COMPLETE event derives **IDLE**, not a stale
 *    RUNNING — a finished replayed run must not read as "running now". RUNNING is claimed ONLY on an open stream/tool.
 *  - **V4 [deriveWindowBadges]:** a RUNNING agent produces **NO** window badge (only the honest ERROR → Attention).
 *    So even if a replayed incomplete event derives RUNNING, it can never surface as a stale live "attention" badge.
 */
class Cyp571ReconnectHonestyTest {

    // V5 — a replayed transcript that ENDED (last AssistantText complete) is IDLE, never a stale RUNNING.
    @Test
    fun deriveStatus_completedReplay_isIdle_notStaleRunning() {
        val replayed = listOf(
            AgentEvent.AssistantText(id = "a1", text = "working…", complete = false, tsMs = 1_000),
            AgentEvent.AssistantText(id = "a1", text = "done.", complete = true, tsMs = 2_000),
        )
        assertEquals(AgentStatus.IDLE, deriveStatus(replayed), "a finished replayed run must not read as RUNNING")
    }

    // V5b — RUNNING is derived ONLY from a genuinely open stream (honest live signal), not guessed.
    @Test
    fun deriveStatus_openStream_isRunning_butNeverGuessed() {
        val open = listOf(AgentEvent.AssistantText(id = "a1", text = "typing", complete = false, tsMs = 1_000))
        assertEquals(AgentStatus.RUNNING, deriveStatus(open))
        assertEquals(AgentStatus.IDLE, deriveStatus(emptyList()), "empty transcript is IDLE, never a guessed status")
    }

    // V4 — a RUNNING agent NEVER produces a window badge (only the honest ERROR does) → no stale-live 'attention'.
    @Test
    fun deriveWindowBadges_runningAgent_producesNoBadge() {
        val badges = deriveWindowBadges(
            focusedId = "comm", commWindowId = "comm", commUnread = 0,
            agentStatuses = mapOf("frontend" to AgentStatus.RUNNING),
            eventTailWindowId = "tail", tailMaxSeverity = null,
        )
        assertFalse(badges.containsKey("frontend"), "a RUNNING (or replayed-incomplete→RUNNING) agent must not badge")
    }

    // V4b — the honest positive: an ERROR agent (non-focused) DOES surface an Attention badge.
    @Test
    fun deriveWindowBadges_errorAgent_surfacesAttention() {
        val badges = deriveWindowBadges(
            focusedId = "comm", commWindowId = "comm", commUnread = 0,
            agentStatuses = mapOf("frontend" to AgentStatus.ERROR),
            eventTailWindowId = "tail", tailMaxSeverity = null,
        )
        assertTrue(badges["frontend"] is WindowBadge.Attention, "an honest ERROR surfaces the Attention badge")
    }
}
