package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.TokenRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CYP-171 / E2.6 (S3) — the operator-pre-provision mint path through [AgentManagement]: token disclosed
 *  ONCE on a remote create (SEC3), revoked on remove (SEC5), never minted for a reserved id (SEC-OP1). */
class RemoteTokenMintTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private class Fix {
        val state = HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po")), HubState.OPERATOR_ID, "default")
        val configs = AgentConfigRegistry(listOf(AgentConfig("po", "PO", Role.PO)))
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ -> FakeSession(id) },
        )
        val registry = TokenRegistry(emptyMap(), operatorToken = "op-token")
        val store = RemoteTokenStore(null) // in-memory
        val issuer = RemoteTokenIssuer(registry, store)
        val mgmt = AgentManagement(state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {}, remoteToken = issuer)
    }

    /** SEC3 — a remote create mints a token, returns it ONCE, and it resolves to the new agent. A normal
     *  (local) create returns no token. (Mutation: skip the `spec.remote` mint → token null → reds.) */
    @Test
    fun remoteCreate_mintsTokenOnce_localCreate_none() {
        val f = Fix()
        val remote = f.mgmt.add(NewAgentSpec("backend", "BE", Role.WORKER, remote = true))
        assertNotNull(remote.token, "a remote create discloses a minted token once")
        assertEquals("backend", f.registry.agentFor(remote.token), "the token resolves to the new agent (token→agentId)")
        assertEquals(remote.token, f.store.all()["backend"], "the token is persisted secret-at-rest")

        val local = f.mgmt.add(NewAgentSpec("frontend", "FE", Role.WORKER))
        assertNull(local.token, "a local create mints no token")
    }

    /** SEC5 — removing a remote agent revokes its token in the registry AND the store → it stops resolving. */
    @Test
    fun remove_revokesTheToken() = runBlocking {
        val f = Fix()
        val token = f.mgmt.add(NewAgentSpec("backend", "BE", Role.WORKER, remote = true)).token!!
        assertEquals("backend", f.registry.agentFor(token))

        f.mgmt.remove("backend", WorktreeFate.KEEP)
        assertNull(f.registry.agentFor(token), "SEC5: a removed agent's token no longer resolves")
        assertTrue(f.store.all()["backend"] == null, "the persisted token is revoked too")
    }

    /** SEC-OP1 — `id="operator"` is rejected BEFORE any mint; nothing is minted or persisted. */
    @Test
    fun create_reservedOperatorId_rejected_nothingMinted() {
        val f = Fix()
        val e = assertFailsWith<BadRequestException> {
            f.mgmt.add(NewAgentSpec("operator", "X", Role.WORKER, remote = true))
        }
        assertEquals("invalid_agent", e.code)
        assertTrue(f.store.all().isEmpty(), "SEC-OP1: nothing minted/persisted for a reserved id")
        assertNull(f.registry.agentFor("operator"), "no token resolves to the operator id")
    }
}
