package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.WireRateLimiter
import com.tneff.cyppieagents.routing.hubWireRoutes
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-199 — the Remote Bridge LIVE-ISH end-to-end proof against the REAL deployable artifact. Unlike the
 * in-process wire tests ([RemoteAcceptTest], [HubWireRoutesTest]) which stand two in-memory clients in for
 * two machines, this boots the REAL `/ws/hub` on a REAL socket (`embeddedServer(Netty)`), then spawns the
 * REAL `:remote-runtime` dist binary as a SEPARATE OS PROCESS — the exact artifact an operator runs — with
 * a fake stream-json Claude-Code (so no real `claude` / API key is needed). Checks:
 *   1+2. connect (valid agent token) + WireHello → REMOTE capability clamp,
 *   3.   a delegated task round-trips OUT as a WireSend into the hub channel (canWrite),
 *   5.   auth fail-closed: a NON-agent token is closed by the server and can NEVER relay.
 * Reconnect / at-least-once (RC2/RC3) is exhaustively covered in-process by [RemoteAcceptTest] (ra3/rc2/ra5),
 * not re-proven over the subprocess.
 *
 * **RUN-gated:** needs `RUN_CYP199=1` + a built dist (`./gradlew :remote-runtime:installDist`) + python3.
 * Self-skips (green no-op) when any is absent, so it never runs in CI by accident.
 */
class RemoteBridgeSubprocessE2eTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    /** A registered AGENT token (tok-backend→backend); the operator token is a NON-agent for /ws/hub. */
    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private fun distBin(): File? {
        System.getenv("CYP199_BRIDGE_BIN")?.let { return File(it).takeIf(File::canExecute) }
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val bin = File(dir, "remote-runtime/build/install/remote-runtime/bin/remote-runtime")
            if (bin.canExecute()) return bin
            dir = dir?.parentFile
        }
        return null
    }

    private fun havePython3(): Boolean =
        runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** A fake stream-json Claude-Code (an executable python script): on the FIRST injected turn emit
     *  system/init, then a success result per turn — driving the connector's CYP-170 lazy-init → BridgeRelay
     *  Gate #6 → a WireSend of the result text. The connector appends stream-json flags; the fake ignores them. */
    private fun writeFakeCc(dir: File): File {
        val f = File(dir, "fake-cc.py")
        f.writeText(
            "#!/usr/bin/env python3\n" +
                "import sys\n" +
                "first = True\n" +
                "for line in sys.stdin:\n" +
                "    if first:\n" +
                "        first = False\n" +
                "        print('{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"bridge-1\"}', flush=True)\n" +
                "    print('{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"bridge-1\",\"result\":\"ACK-NEEDLE-7b3c\"}', flush=True)\n",
        )
        f.setExecutable(true)
        return f
    }

    private fun await(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    private class Fx(
        val hub: Hub,
        val store: InMemoryMessageStore,
        val capReg: CapabilityRegistry,
        val server: EmbeddedServer<*, *>,
        val port: Int,
    )

    /** Boot the REAL `/ws/hub` on a REAL socket with the exact boot wiring (CYP-132 deliverer + CYP-141 route). */
    private fun bootHub(): Fx {
        val store = InMemoryMessageStore()
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), store)
        val sessions = ConnectorSessions()
        val capReg = CapabilityRegistry()
        val deliverer = MessageDeliverer({ hub.state }, { hub.state.activeProjectId }, { sessions }, store, InMemoryDeliveryLog(), scope)
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                hubWireRoutes(
                    hub, registry(), capReg, ProviderRegistry(), WireRateLimiter(), sessions,
                    EventRecorder(InMemoryEventSink(SystemTimeSource()), scope), { "default" },
                )
            }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return Fx(hub, store, capReg, server, port)
    }

    private fun spawnBridge(bin: File, port: Int, token: String, fakeCc: File, log: File): Process =
        ProcessBuilder(bin.absolutePath).apply {
            environment()["HUB_URL"] = "ws://localhost:$port"
            environment()["HUB_AGENT_ID"] = "backend"
            environment()["HUB_TOKEN"] = token
            environment()["CLAUDE_CMD"] = fakeCc.absolutePath
            environment()["BRIDGE_CWD"] = fakeCc.parentFile.absolutePath
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()

    @Test
    fun realBridgeBinary_asSubprocess_connectsClampsAndRelays() {
        if (System.getenv("RUN_CYP199") == null) return
        val bin = distBin() ?: run { println("CYP-199 SKIP: dist not built (./gradlew :remote-runtime:installDist)"); return }
        if (!havePython3()) { println("CYP-199 SKIP: python3 unavailable for the fake CC"); return }
        val work = File.createTempFile("cyp199-ok", "").apply { delete(); mkdirs() }
        val fakeCc = writeFakeCc(work)
        val fx = bootHub()
        val log = File(work, "bridge.log")
        var proc: Process? = null
        try {
            // CHECK 1+2 — the real bridge subprocess connects over the real socket + WireHello is REMOTE-clamped.
            proc = spawnBridge(bin, fx.port, "tok-backend", fakeCc, log)
            assertTrue(await { fx.capReg.get("backend") != null }, "real bridge connected + WireHello registered. log:\n${log.readText()}")
            val caps = fx.capReg.get("backend")!!
            assertEquals(CapabilityStatus.UNAVAILABLE, caps.structuredUsage, "REMOTE clamp: structuredUsage ceiling-UNAVAILABLE")
            assertEquals(CapabilityStatus.AVAILABLE, caps.coordination, "REMOTE clamp: coordination stays AVAILABLE")

            // CHECK 3 — a delegated task round-trips OUT as a WireSend from 'backend' into the hub channel.
            runBlocking { fx.hub.postAsAgent("po", "po-backend", "TASK do the thing") }
            assertTrue(
                await { fx.store.byChannel("po-backend").any { it.from == "backend" && it.body.contains("ACK-NEEDLE-7b3c") } },
                "bridge injected the task → fake CC → relayed the result back as a WireSend from 'backend'. log:\n${log.readText()}",
            )
            println("CYP-199 EVIDENCE(happy, redacted): port=${fx.port} caps(backend)={structuredUsage=${caps.structuredUsage},coordination=${caps.coordination}} po-backend.ACKs=${fx.store.byChannel("po-backend").count { it.body.contains("ACK-NEEDLE") }} token=[REDACTED]")
        } finally {
            proc?.destroyForcibly(); fx.server.stop(200, 200); work.deleteRecursively()
        }
    }

    @Test
    fun realBridgeBinary_nonAgentToken_isFailClosed_cannotRelay() {
        if (System.getenv("RUN_CYP199") == null) return
        val bin = distBin() ?: return
        if (!havePython3()) return
        val work = File.createTempFile("cyp199-bad", "").apply { delete(); mkdirs() }
        val fakeCc = writeFakeCc(work)
        val fx = bootHub()
        val log = File(work, "bridge-bad.log")
        var proc: Process? = null
        try {
            // A bridge with the OPERATOR token — a NON-agent for /ws/hub (agentFor(tok-op)=null → server closes
            // VIOLATED_POLICY before any frame). It must NEVER register caps and NEVER relay.
            proc = spawnBridge(bin, fx.port, "tok-op", fakeCc, log)
            runBlocking { fx.hub.postAsAgent("po", "po-backend", "TASK do the thing") }
            // Give it ample time; the closed link can neither subscribe, receive the deliver, nor WireSend.
            Thread.sleep(6_000)
            assertEquals(null, fx.capReg.get("backend"), "a non-agent token must NOT register any capabilities (auth fail-closed)")
            assertFalse(
                fx.store.byChannel("po-backend").any { it.from == "backend" && it.body.contains("ACK-NEEDLE-7b3c") },
                "the fail-closed bridge relayed NOTHING (no ACK from 'backend'). log:\n${log.readText()}",
            )
            println("CYP-199 EVIDENCE(fail-closed, redacted): non-agent token → caps(backend)=null, po-backend.ACKs=0, token=[REDACTED]")
        } finally {
            proc?.destroyForcibly(); fx.server.stop(200, 200); work.deleteRecursively()
        }
    }
}
