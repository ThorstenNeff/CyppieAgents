package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.routing.aclVisibleEvent
import com.tneff.cyppieagents.routing.commReadSubjectOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-719 — the `aclVisibleEvent` predicate + `commReadSubjectOf` subject mapping, tested platform-neutrally
 * (no route). Each test is a security tooth whose named mutation reddens exactly it:
 *
 *  - T1 `channelNotReadable_denied`      ↔ MUT1 (drop the `acl.canRead` → always visible)
 *  - T2 `channelReadable_visible`        ↔ MUT2 (invert `canRead`)
 *  - T3 `operator_seesEvenUnreadable`    ↔ MUT4 (remove the `isOperator` bypass)
 *  - T4 `foreignProjectChannel_denied`   ↔ MUT5 (project-OR instead of project-AND in AclMatrix)
 *  - T5 `nonCommEvent_visible`           ↔ MUT2 (the non-comm fall-through must stay `true`)
 *  - T6a `commSent_noChannel_failClosed` ↔ MUT6 (channel-less `comm.*` → `true` instead of fail-closed)
 *  - T6b `commReceived_jsonNull` (F-C)   ↔ MUT3 (keying on `COMM_SENT` only → `COMM_RECEIVED` leaks)
 *  - `subjectMapping` / `killswitchEdge` ↔ §3 mis-map a principal class / `MachineAgent(null)`→OPERATOR escalation
 */
class Cyp719AclFilterPredicateTest {

    private val active = "alpha"

    // backend may read chY, NOT chX; both channels live in the active project.
    private val acl = AclMatrix(
        channels = listOf(
            Channel("chX", "X", ChannelKind.GROUP, listOf("backend"), projectId = active),
            Channel("chY", "Y", ChannelKind.GROUP, listOf("backend"), projectId = active),
        ),
        entries = listOf(
            AclEntry("chX", "backend", canRead = false, canWrite = false, projectId = active),
            AclEntry("chY", "backend", canRead = true, canWrite = false, projectId = active),
        ),
        activeProjectId = active,
    )

    private fun ev(type: EventType, channel: String?, project: String = active): Event {
        val detail = if (channel == null) JsonObject(emptyMap()) else buildJsonObject { put("channel", channel) }
        return Event(id = "e", ts = 1, seq = 1, agentId = "x", projectId = project, type = type, severity = Severity.INFO, detail = detail)
    }

    @Test fun t5_nonCommEvent_visibleToNonOperator() {
        assertTrue(aclVisibleEvent(ev(EventType.TOOL_CALL, channel = null), "backend", isOperator = false, acl))
    }

    @Test fun t6a_commSent_noChannel_failClosed() {
        assertFalse(aclVisibleEvent(ev(EventType.COMM_SENT, channel = null), "backend", isOperator = false, acl))
    }

    // F-C: a `comm.*` event whose `detail.channel` is JsonNull has no readable channel → fail-closed. JsonNull IS a
    // JsonPrimitive, but `contentOrNull` is null → the predicate must NOT treat it as a channel string.
    @Test fun t6b_commReceived_channelJsonNull_failClosed() {
        val e = Event(
            id = "e", ts = 1, seq = 1, agentId = "x", projectId = active,
            type = EventType.COMM_RECEIVED, severity = Severity.INFO,
            detail = buildJsonObject { put("channel", JsonNull) },
        )
        assertFalse(aclVisibleEvent(e, "backend", isOperator = false, acl))
    }

    @Test fun t1_commEvent_channelNotReadable_denied() {
        assertFalse(aclVisibleEvent(ev(EventType.COMM_SENT, channel = "chX"), "backend", isOperator = false, acl))
    }

    @Test fun t2_commEvent_channelReadable_visible() {
        assertTrue(aclVisibleEvent(ev(EventType.COMM_SENT, channel = "chY"), "backend", isOperator = false, acl))
    }

    @Test fun t3_operator_seesEvenUnreadableChannel() {
        assertTrue(aclVisibleEvent(ev(EventType.COMM_SENT, channel = "chX"), HubState.OPERATOR_ID, isOperator = true, acl))
    }

    // T4 project-AND-acl: a foreign-project channel is denied DESPITE an explicit canRead=true entry — AclMatrix
    // drops the out-of-scope channel AND its entry, so it can never be OR'd back in.
    @Test fun t4_foreignProjectChannel_deniedDespiteCanReadGrant() {
        val crossAcl = AclMatrix(
            channels = listOf(Channel("chBeta", "B", ChannelKind.GROUP, listOf("backend"), projectId = "beta")),
            entries = listOf(AclEntry("chBeta", "backend", canRead = true, canWrite = false, projectId = "beta")),
            activeProjectId = active, // active = alpha; the channel + entry live in beta → out of scope
        )
        assertFalse(crossAcl.canRead("chBeta", "backend"), "sanity: a foreign-project channel is not readable even with a grant")
        assertFalse(
            aclVisibleEvent(ev(EventType.COMM_SENT, channel = "chBeta", project = "beta"), "backend", isOperator = false, crossAcl),
        )
    }

    // §3 subject mapping (the unit half of the cross-surface equivalence tooth): every principal class that can
    // reach the read path maps to its canonical ACL subject. Mis-mapping any class reddens this.
    @Test fun subjectMapping_perPrincipalClass() {
        assertEquals(HubState.OPERATOR_ID, commReadSubjectOf(AuthPrincipal.MachineOperator))
        assertEquals("backend", commReadSubjectOf(AuthPrincipal.MachineAgent("backend")))
        assertEquals(HubState.OPERATOR_ID, commReadSubjectOf(AuthPrincipal.Human("op-1", AuthRole.OPERATOR)))
        assertEquals("mem-1", commReadSubjectOf(AuthPrincipal.Human("mem-1", AuthRole.MEMBER)))
    }

    // The disabled-operator kill-switch downgrade (CYP-186 C.2 → MachineAgent(null)) has NO ACL read-subject and
    // must NOT escalate to the operator: it is fail-closed out of every channel-scoped comm event (but still reads
    // non-comm metadata, like any MEMBER). Mutation `MachineAgent(null) → OPERATOR_ID` reddens the first assert.
    @Test fun killswitchEdge_machineAgentNull_hasNoSubject_andSeesNoComm() {
        assertNull(commReadSubjectOf(AuthPrincipal.MachineAgent(null)), "disabled-operator downgrade → no ACL subject")
        assertFalse(aclVisibleEvent(ev(EventType.COMM_SENT, channel = "chY"), null, isOperator = false, acl), "null subject → no comm")
        assertTrue(aclVisibleEvent(ev(EventType.TOOL_CALL, channel = null), null, isOperator = false, acl), "null subject → non-comm still visible")
    }
}
