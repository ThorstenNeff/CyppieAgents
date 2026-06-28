package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclGuard
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure decision table for the PO-lockout guardrail (CYP-49). Lives in `:core` commonTest so the
 * exact logic the server enforces is verified platform-neutrally, like [AclMatrix].
 *
 * The guard reads the **recomputed** [AclMatrix] (never the request payload), so the three lockout
 * vectors the Reviewer flagged — `canWrite`, `canRead`, and removed `members` — are all exercised
 * here against one fail-closed answer.
 */
class AclGuardTest {
    private val po = "po"
    private val hubIds = setOf("po-frontend", "po-backend")

    private val channels = listOf(
        Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend")),
        Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("po", "backend")),
    )

    /** The healthy hub-and-spoke baseline: PO read+write on both spokes, workers on their own. */
    private fun baselineEntries() = listOf(
        AclEntry("po-frontend", "po", canRead = true, canWrite = true),
        AclEntry("po-frontend", "frontend", canRead = true, canWrite = true),
        AclEntry("po-backend", "po", canRead = true, canWrite = true),
        AclEntry("po-backend", "backend", canRead = true, canWrite = true),
    )

    private fun matrixWith(vararg overrides: AclEntry): AclMatrix {
        val merged = baselineEntries().filterNot { e ->
            overrides.any { it.channelId == e.channelId && it.agentId == e.agentId }
        } + overrides
        return AclMatrix(channels, merged)
    }

    @Test
    fun healthyTopologyIsNotLockout() {
        assertNull(AclGuard.lockedOutPoHubChannel(matrixWith(), po, hubIds))
    }

    @Test
    fun workerRevokeIsNeverLockout() {
        // The whole point: revoking a worker entirely leaves the PO's hubs intact → allowed.
        val m = matrixWith(AclEntry("po-backend", "backend", canRead = false, canWrite = false))
        assertNull(AclGuard.lockedOutPoHubChannel(m, po, hubIds))
    }

    @Test
    fun revokingPoWriteIsLockout() {
        val m = matrixWith(AclEntry("po-backend", "po", canRead = true, canWrite = false))
        assertEquals("po-backend", AclGuard.lockedOutPoHubChannel(m, po, hubIds))
    }

    @Test
    fun revokingPoReadIsLockout() {
        // Adjacent vector: canWrite-only protection would miss this; the matrix check catches it.
        val m = matrixWith(AclEntry("po-frontend", "po", canRead = false, canWrite = true))
        assertEquals("po-frontend", AclGuard.lockedOutPoHubChannel(m, po, hubIds))
    }

    @Test
    fun droppingPoFromMembersIsLockout() {
        // members vector: with the PO no longer a member, AclMatrix denies read+write (fail-closed),
        // so the guard flags it — even though no entry flag changed. (Not reachable via PUT /api/acl
        // in the MVP, but the guard is robust to it by construction.)
        val noPoMembers = listOf(
            Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend")),
            Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("backend")), // PO dropped
        )
        val m = AclMatrix(noPoMembers, baselineEntries())
        assertEquals("po-backend", AclGuard.lockedOutPoHubChannel(m, po, hubIds))
    }
}
