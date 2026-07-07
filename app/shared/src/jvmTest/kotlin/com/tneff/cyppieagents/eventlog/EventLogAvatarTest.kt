package com.tneff.cyppieagents.eventlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.AvatarTags
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/**
 * CYP-224 — the event-log row honours the sender's custom colour/avatar/role via the threaded `agents` map
 * (comm/titlebar parity, §5 one resolver), and falls back to the slot default for an unknown sender.
 *
 * NOTE on scope: the avatar's custom COLOUR/IMAGE is visual-only — not semantically assertable (the CYP-216
 * `AgentAvatarViewTest` has the same limitation, so it smoke-tests "the custom-colour path is taken without
 * crashing"). These teeth therefore verify the plumbing renders through the map, the ratified design decision
 * (displayName stays = id → id-based initials, NOT the agent's name), and the unknown-sender fallback (no crash).
 */
@OptIn(ExperimentalTestApi::class)
class EventLogAvatarTest {

    private fun event(agentId: String) = Event(
        id = "e1", ts = 1_000, seq = 1, agentId = agentId, projectId = "team-1",
        type = EventType.TURN_START, severity = Severity.INFO,
        correlationId = null, sessionId = null, detail = JsonObject(emptyMap()),
    )

    @Test
    fun knownAgent_rendersAvatarThroughCustomPath_keepsIdInitials() = runComposeUiTest {
        val agents = mapOf(
            "backend" to Agent(id = "backend", name = "Backend Dev", role = Role.WORKER, worktree = "backend", color = "#3B82F6"),
        )
        setContent {
            MaterialTheme {
                EventRow(event = event("backend"), rowTag = "row", qualifierTag = "q", byIdTag = "byid", agents = agents)
            }
        }
        onNodeWithTag(AvatarTags.avatar("backend")).assertExists() // renders through the custom-colour path, no crash
        // Design decision (CYP-224): the avatar takes the agent's COLOUR but displayName stays = id → id-based
        // initials ("BA"), consistent with the agent-id row text. If it switched to the agent NAME → "BD" → REDs.
        onNodeWithText("BA").assertExists()
    }

    @Test
    fun unknownAgent_fallsBackToSlotDefault_noCrash() = runComposeUiTest {
        // Empty map / unknown sender → null colour/avatar/role → the deterministic slot default (unchanged fallback).
        setContent {
            MaterialTheme {
                EventRow(event = event("backend"), rowTag = "row", qualifierTag = "q", byIdTag = "byid", agents = emptyMap())
            }
        }
        onNodeWithTag(AvatarTags.avatar("backend")).assertExists()
        onNodeWithText("BA").assertExists()
    }
}
