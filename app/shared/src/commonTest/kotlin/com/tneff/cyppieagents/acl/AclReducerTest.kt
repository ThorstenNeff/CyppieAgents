package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure ACL-matrix reducer logic (CYP-48), aligned to the `:core AclMatrix` decision (deny-wins/fail-closed). */
class AclReducerTest {

    private val hubSpoke = Channel("po-frontend", "PO/Front", ChannelKind.HUB, listOf("po", "frontend"))
    private val group = Channel("team", "Team", ChannelKind.GROUP, listOf("po", "frontend"))
    private val po = Agent("po", "PO", Role.PO, "po")
    private val frontend = Agent("frontend", "Front", Role.WORKER, "frontend")
    private val agents = listOf(po, frontend)

    @Test
    fun cells_effectiveGrants_failClosedForMemberWithoutEntry() {
        val entries = listOf(AclEntry("po-frontend", "po", canRead = true, canWrite = true))
        val cells = AclReducer.cells(listOf(hubSpoke), agents, entries).getValue("po-frontend")
        val poCell = cells.first { it.agentId == "po" }
        val frontCell = cells.first { it.agentId == "frontend" }
        assertTrue(poCell.isMember && poCell.canRead && poCell.canWrite)
        assertTrue(frontCell.isMember)
        assertFalse(frontCell.canRead, "member without an entry is fail-closed")
    }

    @Test
    fun nonMemberCell_isNotAMember() {
        val solo = Channel("solo", "Solo", ChannelKind.DIRECT, listOf("po"))
        val cells = AclReducer.cells(listOf(solo), agents, emptyList()).getValue("solo")
        assertFalse(cells.first { it.agentId == "frontend" }.isMember)
    }

    @Test
    fun conflict_denyWins() {
        val entries = listOf(
            AclEntry("po-frontend", "frontend", canRead = true, canWrite = true),
            AclEntry("po-frontend", "frontend", canRead = false, canWrite = true),
        )
        val cell = AclReducer.cells(listOf(hubSpoke), agents, entries).getValue("po-frontend").first { it.agentId == "frontend" }
        assertTrue(cell.conflict)
        assertFalse(cell.canRead, "deny-wins on duplicate entries")
        assertTrue(cell.canWrite)
    }

    @Test
    fun poCritical_onlyOnHubChannels() {
        val hub = AclReducer.cells(listOf(hubSpoke), agents, listOf(AclEntry("po-frontend", "po", true, true))).getValue("po-frontend")
        assertTrue(hub.first { it.agentId == "po" }.poCritical)
        val grp = AclReducer.cells(listOf(group), agents, listOf(AclEntry("team", "po", true, true))).getValue("team")
        assertFalse(grp.first { it.agentId == "po" }.poCritical, "non-HUB PO cell is not advisory-critical (matches server scope)")
    }

    @Test
    fun wouldLockoutPo_onlyPoGrantOffOnHub() {
        assertTrue(AclReducer.wouldLockoutPo("po-frontend", po, newValue = false, listOf(hubSpoke)))
        assertFalse(AclReducer.wouldLockoutPo("po-frontend", po, newValue = true, listOf(hubSpoke)), "granting never locks out")
        assertFalse(AclReducer.wouldLockoutPo("po-frontend", frontend, newValue = false, listOf(hubSpoke)), "worker toggles untouched")
        assertFalse(AclReducer.wouldLockoutPo("team", po, newValue = false, listOf(group)), "non-HUB not guarded")
    }

    @Test
    fun upsert_isLastWinsByKey_andRemoveDrops() {
        val a = AclEntry("c", "x", canRead = true, canWrite = true)
        val b = AclEntry("c", "x", canRead = false, canWrite = false)
        assertEquals(listOf(b), AclReducer.upsert(listOf(a), b))
        assertTrue(AclReducer.remove(listOf(b), "c", "x").isEmpty())
    }

    @Test
    fun presetDiff_onlyNotYetSatisfiedMemberCells() {
        val entries = listOf(AclEntry("po-frontend", "po", true, true))
        val diff = AclReducer.presetDiff(listOf(hubSpoke), entries)
        assertTrue(diff.any { it.agentId == "frontend" }, "frontend not yet R/W → in the diff")
        assertFalse(diff.any { it.agentId == "po" }, "po already satisfied → not in the diff")
    }

    @Test
    fun statusOf_mapsConnectionEvents() {
        assertEquals(ConnectionStatus.LIVE, AclReducer.statusOf(AclLiveEvent.Connected))
        assertEquals(ConnectionStatus.DISCONNECTED, AclReducer.statusOf(AclLiveEvent.Disconnected))
        assertEquals(null, AclReducer.statusOf(AclLiveEvent.EntryChanged(AclEntry("c", "x", true, true))))
    }
}
