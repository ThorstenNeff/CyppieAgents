package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.routing.WireEventIngest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S5 / G4-5 — the server-enforced ingress hardening. The bridge is UNTRUSTED (a malicious user handcrafts
 * the frame), so [WireEventIngest] must, server-side: whitelist-DROP non-`RATE_LIMIT_KEYS`, size-cap every
 * opaque value + the tool name, stamp `source=remote` itself (never a frame field), and attribute to the
 * passed (token-derived) agentId. (G4-4's no-caps-escalation is structural: `toDraft` returns an EventDraft.)
 */
class WireEventIngestTest {

    private fun det(d: com.tneff.cyppieagents.events.EventDraft, k: String) = d.detail[k]?.jsonPrimitive?.content

    /** G4-5 whitelist-DROP: an attacker key is dropped; only the projector's RATE_LIMIT_KEYS survive. */
    @Test
    fun rateLimit_dropsNonWhitelistedKeys() {
        val draft = WireEventIngest.toDraft(
            "backend", "default",
            WireEvent(WireEventType.RATE_LIMIT, rateLimit = mapOf("status" to "blocked", "evilKey" to "sk-ant-leak", "overageStatus" to "rejected")),
        )
        assertEquals(EventType.ERROR_RATELIMIT, draft.type)
        assertEquals("blocked", det(draft, "status"), "a whitelisted key survives")
        assertNull(draft.detail["evilKey"], "G4-5: a non-whitelisted key is DROPPED")
        assertNull(draft.detail["overageStatus"], "overageStatus is deliberately not in the whitelist")
    }

    /** G4-5 size-cap: an oversized opaque value (secret/10MB smuggling) is capped. */
    @Test
    fun sizeCaps_values_andToolName() {
        val huge = "x".repeat(50_000)
        val rl = WireEventIngest.toDraft("backend", "default", WireEvent(WireEventType.RATE_LIMIT, rateLimit = mapOf("status" to huge)))
        assertTrue(det(rl, "status")!!.length <= WireEventIngest.VALUE_CAP, "G4-5: value size-capped")
        val tc = WireEventIngest.toDraft("backend", "default", WireEvent(WireEventType.TOOL_CALL, tool = huge))
        assertTrue(det(tc, "toolName")!!.length <= WireEventIngest.VALUE_CAP, "G4-5: tool name size-capped")
    }

    /** source=remote is SERVER-stamped (provenance), and the event is attributed to the passed agentId. */
    @Test
    fun stampsSourceRemote_andAttributesToAgentId() {
        val draft = WireEventIngest.toDraft("backend", "proj-1", WireEvent(WireEventType.TOOL_CALL, tool = "Bash"))
        assertEquals("remote", det(draft, "source"), "source=remote is server-stamped, not a frame field")
        assertEquals("backend", draft.agentId, "attributed to the (token-derived) agentId")
        assertEquals("proj-1", draft.projectId)
        assertEquals(EventType.TOOL_CALL, draft.type)
        assertEquals("Bash", det(draft, "toolName"))
    }

    /** TOOL_RESULT maps to its EventType; only the tool NAME is kept (never tool I/O). */
    @Test
    fun toolResult_keepsOnlyTheName() {
        val draft = WireEventIngest.toDraft("backend", "default", WireEvent(WireEventType.TOOL_RESULT, tool = "Bash"))
        assertEquals(EventType.TOOL_RESULT, draft.type)
        assertEquals("Bash", det(draft, "toolName"))
        assertEquals("remote", det(draft, "source"))
    }
}
