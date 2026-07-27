package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.RemoteTokenIssuer
import com.tneff.cyppieagents.boot.RemoteTokenStore
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.WireRateLimiter
import com.tneff.cyppieagents.routing.hubWireRoutes
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-172 — the AT-CONNECT roster cross-check, pinned at the `/ws/hub` auth outcome. **PRE-FIX TOOTH.**
 *
 * ### The property (not the implementation)
 * A bearer token must only authenticate while its agentId is a **live participant of the active roster**.
 * `agentFor` (routing/Auth.kt:32) is a bare `token → agentId` map lookup with no roster validation, so today
 * a token outlives its agent's membership. ② states the property that must hold once the cross-check
 * lands; it is RED until then. Nothing here asserts *how* the check is implemented.
 *
 * ### The reachable path (measured before this tooth was shaped — two dead ends ruled out)
 *  - **Single-agent removal is CLOSED.** `AgentManagement.remove` calls `remoteToken?.revoke(id)`
 *    (AgentManagement.kt:246) → `agentFor` goes null. A "remove the agent, token still works" tooth would
 *    be **vacuously green**. Not built.
 *  - **`ProjectDeleter` is NOT the vector.** `ProjectGuard.validateDelete` (ProjectGuard.kt:38-43) refuses
 *    to delete the ACTIVE project (`active_project_protected`), so a deleted project's agents were never in
 *    `state.agents` — they are already stashed. The deleter sits *behind* the hole, not at it.
 *  - **★ The live vector is the PROJECT SWITCH.** `POST /api/projects/switch` → `ProjectSwitcher.switch` →
 *    `state.rescope(newProjectId)` (HubState.kt:277-286) moves the outgoing project's agents into
 *    `stashedAgents` and swaps `agents` for the incoming project's list. **Nothing in that path touches
 *    `TokenRegistry`.** A remote agent minted under project A therefore keeps a valid credential while
 *    `state.agent(id) == null`. CYP-690's own comment names this case (BootOrchestrator.kt:922-925).
 *
 * ### How far the stale agent gets today
 *  - ✅ connects, completes the handshake, occupies a session slot, mutates capability/provider registries;
 *  - ✅ **writes the event log of the project it was never part of** — `HubWireRoutes.kt:218-220` records
 *    with `activeProjectId()`, and `EventRecorder.record` is a plain queue offer with no ACL and no roster
 *    check. That is the sharpest "can act" consequence;
 *  - ❌ cannot post or read messages — `Hub.postAsAgent` is `acl.canWrite`-gated and `AclMatrix` filters
 *    entries to the active project, fail-closed, with no cross-project OR (AclMatrix.kt:40-41, :45).
 *    **That block is verified-good and ④ guards it against regression.**
 *
 * ### Severity, stated honestly
 * Durable **within a runtime**, not across a restart: CYP-690's boot orphan-purge sweeps exactly these
 * not-in-roster bindings at the next boot, so a restart is the backstop. The sharper fact is
 * **unrevocability**: `AgentManagement.remove` throws `agent_not_found` (AgentManagement.kt:236-238) because
 * `state.agent(id)` is null for a stashed agent, so the operator cannot revoke this valid credential by the
 * normal route at all — only by switching back, or by restarting.
 *
 * ### A note on the fix seam (a semantic choice, not a free win)
 * `state.agents` is the **active** roster, and there is **no** per-project lookup that covers runtime remote
 * agents — `ProjectAgentStore` deliberately excludes them (`toStore = projectAgents != null && !spec.remote`,
 * AgentManagement.kt:178). So a cross-check against `state.agents` also refuses the bridge of a *legitimately
 * inactive* project. Under Topology-A (one hub serves the active project) that is defensible, but it is a
 * decision to make deliberately — it is the same collateral direction that CYP-690's first tooth was blind to.
 *
 * ### Fixture fidelity
 * The stale-agent state is produced by the **two production calls `AgentManagement.add` makes for a remote
 * agent** — `state.addAgent(agent)` (:181) and `remoteToken.issue(agent.id)` (:191) — followed by
 * `state.rescope("B")`, the single production line the switch route drives. No HubState internals are
 * hand-edited. (The full `AgentManagement` graph needs a `LifecycleManager` + `AgentConfigRegistry`; it would
 * mint through this identical `RemoteTokenIssuer` call, so it is wiring, not fidelity.)
 */
class Cyp172AtConnectRosterCrossCheckTest {

    private val provider = ProviderInfo("claude", "Claude")
    private fun allAvailable() =
        Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)

    private fun projectAAgents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )
    private fun projectBAgents() = listOf(
        Agent("po-b", "PO-B", Role.PO, "po-b"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
    )

    private class Fx(
        val hub: Hub,
        val state: HubState,
        val events: InMemoryEventSink,
        val staleToken: String,
        val liveTokenB: String,
    )

    /**
     * Build the post-switch state over REAL production calls:
     *   1. project A active, a remote agent `byoa` added + its token minted (what `AgentManagement.add` does);
     *   2. project B's agents seeded, then `state.rescope("B")` — the line `ProjectSwitcher.switch` executes;
     *   3. `byoa` is now stashed (NOT in `state.agents`) while its token is still bound in the registry.
     */
    private fun ApplicationTestBuilder.installPostSwitch(): Fx {
        val store = InMemoryMessageStore()
        val state = HubState.hubAndSpoke(projectAAgents(), HubState.OPERATOR_ID, activeProjectId = "A")
        val hub = Hub(state, store)
        val tokenRegistry = TokenRegistry(emptyMap(), operatorToken = "tok-op", loopbackPosture = true)
        val issuer = RemoteTokenIssuer(tokenRegistry, RemoteTokenStore(null))

        // (1) the remote agent is created while A is active — the two calls AgentManagement.add makes.
        state.addAgent(Agent("byoa", "BYOA", Role.WORKER, "byoa"))
        val staleToken = issuer.issue("byoa")

        // (2) seed B's roster into the stash, then perform the real switch.
        state.rescope("B")
        projectBAgents().forEach { state.addAgent(it) }
        val liveTokenB = issuer.issue("frontend") // a token for an agent that IS in the active roster

        val eventSink = InMemoryEventSink(SystemTimeSource())
        // EventRecorder.start() launches the single writer coroutine; without it `record()` only fills the
        // queue and NOTHING ever reaches the sink. (Other wire tests omit it because they never assert on
        // events — here it is load-bearing: the Ⅰ barrier below caught its absence rather than reporting a
        // vacuous "no stale events".)
        val recorder = EventRecorder(eventSink, CoroutineScope(Dispatchers.Default)).also { it.start() }
        application {
            install(WebSockets)
            routing {
                hubWireRoutes(
                    hub, tokenRegistry, CapabilityRegistry(), ProviderRegistry(), WireRateLimiter(),
                    ConnectorSessions(),
                    recorder,
                    { state.activeProjectId },
                )
            }
        }
        return Fx(hub, state, eventSink, staleToken, liveTokenB)
    }

    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }
    private fun frame(f: WireFrame) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame) = send(Frame.Text(frame(f)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame

    /** The wire ACCEPTS [token]: the handshake completes with an ack. Diagnoses a refusal outside the session. */
    private suspend fun ApplicationTestBuilder.assertWireAccepts(token: String, why: String) {
        var ack: WireFrame? = null
        withTimeout(20_000) {
            try {
                wsClient(this@assertWireAccepts).webSocket("/ws/hub?token=$token") {
                    sendFrame(WireHello(allAvailable(), provider))
                    ack = recv()
                }
            } catch (e: Exception) { /* refused → ack stays null, asserted below */ }
        }
        assertTrue(ack != null, "$why — but the socket was REFUSED: no WireAck arrived")
        assertIs<WireAck>(ack, why)
    }

    /**
     * The wire REFUSES [token] **at the auth check, before any frame**.
     *
     * ⚠️ The close CODE alone is not discriminating: `/ws/hub` closes `VIOLATED_POLICY` both for the auth
     * rejection (HubWireRoutes.kt:79 "unauthorized") and for the handshake timeout (:105 "handshake timeout").
     * A still-valid token is ACCEPTED, sends nothing, and is then timed out — same code. So we pin the REASON.
     * (This exact trap made the first CYP-690 tooth vacuously green; carried forward deliberately.)
     */
    private suspend fun ApplicationTestBuilder.assertWireRefusedAtAuth(token: String, why: String) {
        withTimeout(20_000) {
            wsClient(this@assertWireRefusedAtAuth).webSocket("/ws/hub?token=$token") {
                val reason = closeReason.await()
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, why)
                assertEquals("unauthorized", reason?.message, "$why — and refused by the AUTH check, not by the handshake timeout")
            }
        }
    }

    // ---------- ① ANCHOR — an agent in the ACTIVE roster authenticates (green now, must stay green) ----------
    /**
     * The positive control, and the collateral guard for the fix: a cross-check must not lock out a
     * legitimate active-roster agent. Without this, ②'s "refused" could not be distinguished from a
     * harness that refuses everything.
     */
    @Test
    fun cyp172_1_anchor_activeRosterAgent_authenticatesAndHandshakes() = testApplication {
        val fx = installPostSwitch()
        assertTrue(fx.state.agent("frontend") != null, "precondition: 'frontend' IS in the active roster")
        assertWireAccepts(fx.liveTokenB, "ANCHOR: an agent in the ACTIVE roster must authenticate")
    }

    // ---------- ④ the hole is now CLOSED + the ACL block stays verified-good (green after the fix) ----------
    /**
     * REVISED when the CYP-172 fix landed (the pre-fix ④ was flagged "must be REVISITED": it asserted the stale
     * agent is ACCEPTED at connect, which the fix now refuses). This keeps ④'s two intents, both post-fix:
     *  - **the hole is CLOSED** — the stale agent (bound token, not in the active roster) is now refused AT
     *    CONNECT (the characterisation that used to say "accepted" is inverted by the fix);
     *  - **the ACL block is still verified-good** — the fix must not become the ONLY defense. An agent that IS
     *    in the active roster (`frontend`) still has a `WireSend` into a channel it does not own fail-closed by
     *    the ACL (member AND canWrite, no cross-project OR). This preserves the AclMatrix regression guard the
     *    pre-fix ④ carried, now via an in-roster sender since the stale agent can no longer reach the send path.
     * Kept separate from ②/③ so the record of the ACL layer is not silently erased by the connect-refusal fix.
     */
    @Test
    fun cyp172_4_afterFix_staleRefusedAtConnect_andAclStillFailClosesUnownedChannel() = testApplication {
        val fx = installPostSwitch()
        assertTrue(fx.state.agent("byoa") == null, "precondition: 'byoa' is NOT in the active roster after the switch")
        // (a) the hole is CLOSED: the stale agent is now refused at the auth check (was ACCEPTED pre-fix).
        assertWireRefusedAtAuth(fx.staleToken, "CYP-172 fix: a stale (not-in-active-roster) token must be refused at connect")
        // (b) the ACL block stays verified-good — an IN-ROSTER agent's send into a channel it does not own is
        //     still fail-closed (AclMatrix). "po-backend" is project A's spoke, absent from active project B.
        var sendOutcome: WireFrame? = null
        withTimeout(20_000) {
            wsClient(this@testApplication).webSocket("/ws/hub?token=${fx.liveTokenB}") {
                sendFrame(WireHello(allAvailable(), provider))
                assertIs<WireAck>(recv(), "the in-roster control agent authenticates and handshakes")
                sendFrame(WireSend("po-backend", "unowned-channel-probe"))
                sendOutcome = recv()
            }
        }
        assertIs<WireError>(sendOutcome, "VERIFIED-GOOD: the ACL fail-closes a send into a channel the agent does not own")
    }

    // ---------- ② ACCEPTANCE (RED until the fix) — the stale token must be refused AT CONNECT ----------
    /**
     * ★ The CYP-172 property. After a project switch, a token whose agentId is no longer a live participant
     * must not authenticate. RED today: `agentFor` does no roster cross-check, so the socket is accepted and
     * this fails on the close-reason assertion.
     *
     * Un-ignore this when the at-connect cross-check lands — it is the fix's acceptance test.
     */
    @Test
    fun cyp172_2_staleAgentToken_isRefusedAtConnect_afterProjectSwitch() = testApplication {
        val fx = installPostSwitch()
        assertTrue(fx.state.agent("byoa") == null, "precondition: 'byoa' is NOT in the active roster")
        assertWireRefusedAtAuth(fx.staleToken, "CYP-172: a token whose agent is not in the active roster must NOT authenticate")
    }

    // ---------- ③ ACCEPTANCE (RED until the fix) — no cross-project event-log contamination ----------
    /**
     * ★ The sharpest consequence: a stale agent's `WireEvent` is recorded into the **ACTIVE** project's event
     * log, stamped with `activeProjectId()` (`HubWireRoutes.kt:218-220`) under an agentId that is not in that
     * project's roster. `EventRecorder.record` (EventRecorder.kt:48-52) is a plain queue offer — no ACL, no
     * roster check. That is cross-project **contamination**, not merely "the credential still lives".
     *
     * ### Why this is stated as an outcome and not as an absence-by-hope
     * The property is an ABSENCE ("the stale agent contributes nothing"), and `EventRecorder` drains
     * **asynchronously** — so a naive `assertTrue(empty)` is green whenever the queue merely hasn't drained,
     * which is green for the wrong reason. Two guards make the claim real:
     *
     *  **Ⅰ Proven window coverage via a BARRIER.** A planted, form-matched control event is sent by a LIVE
     *    active-roster agent (`frontend`) through the SAME route, in the SAME run, **AFTER** the stale agent's
     *    event. The recorder drains FIFO, so observing the control proves the queue has drained *past* the
     *    stale write. Ordering is the whole point: a control sent BEFORE would prove nothing about a later
     *    write. Ⅰ failing (control never appears) means the harness could not observe anything → the result is
     *    **INCONCLUSIVE, not PASS**, and the assertion below says so.
     *  **Ⅱ The absence itself**, which is only meaningful once Ⅰ holds.
     *
     * ④ additionally proves the stale agent genuinely gets in today, so a green here after the fix means
     * "refused", not "the fixture never connected anything".
     */
    @Test
    fun cyp172_3_staleAgent_writesNoEventIntoTheActiveProjectsLog() = testApplication {
        val fx = installPostSwitch()

        // The stale agent attempts its event FIRST (once the fix lands the connect itself is refused).
        try {
            withTimeout(20_000) {
                wsClient(this@testApplication).webSocket("/ws/hub?token=${fx.staleToken}") {
                    sendFrame(WireHello(allAvailable(), provider))
                    recv()
                    sendFrame(WireEvent(signal = WireEventType.TOOL_CALL, tool = "cyp172-stale-probe"))
                }
            }
        } catch (e: Exception) { /* refused after the fix — that is the property, not a failure */ }

        // Ⅰ BARRIER: the control is sent AFTER the stale write, by an agent that IS in the active roster.
        // The poll happens INSIDE the session — closing the socket can tear the server-side handler before it
        // has consumed the frame, which would make the barrier fail for a harness reason rather than a real one.
        var controlLanded = false
        withTimeout(30_000) {
            wsClient(this@testApplication).webSocket("/ws/hub?token=${fx.liveTokenB}") {
                sendFrame(WireHello(allAvailable(), provider))
                assertIs<WireAck>(recv(), "the control agent must be admitted — it IS in the active roster")
                sendFrame(WireEvent(signal = WireEventType.TOOL_CALL, tool = "cyp172-live-control"))
                controlLanded = withTimeoutOrNull(15_000) {
                    while (fx.events.query(EventFilter(agentId = "frontend"), Page(limit = 500)).events.isEmpty()) delay(50)
                    true
                } ?: false
            }
        }
        val observed = fx.events.query(EventFilter(), Page(limit = 500)).events
        assertTrue(
            controlLanded,
            "INCONCLUSIVE (not a pass): the planted control event never landed, so the event log was never " +
                "observed to drain — an empty stale result would prove nothing about the stale write. " +
                "Event log held ${observed.size} event(s), agentIds=${observed.map { it.agentId }.distinct()}",
        )

        // Ⅱ the absence, now load-bearing.
        val fromStale = fx.events.query(EventFilter(agentId = "byoa"), Page(limit = 500)).events
        assertTrue(
            fromStale.isEmpty(),
            "CYP-172: a stale cross-project agent must contribute NO events to the active project's log, " +
                "but ${fromStale.size} landed (stamped with the active project it was never a member of)",
        )
    }
}
