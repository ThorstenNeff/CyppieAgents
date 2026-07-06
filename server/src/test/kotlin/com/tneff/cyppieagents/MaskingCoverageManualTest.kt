package com.tneff.cyppieagents

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MANUAL S8 masking-coverage proof: a real `claude` runs a Bash tool that emits a secret-shaped
 * value (fake `sk-ant-…`), and we assert it is `***REDACTED***` in ALL THREE egress paths —
 * the /ws/agent event stream, the mediated hub post, and the audit log. Closes the only live
 * residual (whether real `claude` emits secrets in the exact fields EventMasking traverses).
 *
 * Skipped unless RUN_MASK_PROOF=1. Auth = OAuth subscription (option b), not D3. Tight tools (Bash).
 */
class MaskingCoverageManualTest {

    private val sentinel = "sk-ant-FAKEcafe0123456789babe0000" // matches SecretMasker; NOT a real key
    private val rawNeedle = "FAKEcafe0123456789babe0000"

    @Test
    fun secretFlowingThroughAToolIsRedactedOnEveryEgress() {
        assumeTrue("set RUN_MASK_PROOF=1", System.getenv("RUN_MASK_PROOF") == "1")
        runBlocking {
            // Capture the audit egress via a logback list appender on the "comm.audit" logger.
            val auditLogger = LoggerFactory.getLogger("comm.audit") as LogbackLogger
            val auditCapture = ListAppender<ILoggingEvent>().apply { start() }
            auditLogger.addAppender(auditCapture)

            val agents = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))
            val hub = Hub(HubState.hubAndSpoke(agents), InMemoryMessageStore())
            val registry = SessionRegistry()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val scratch = Files.createTempDirectory("mask-proof").toFile()
            java.io.File(scratch, "backend").mkdirs()

            val connector = ClaudeCodeConnector(
                spawner = ProcessBuilderSpawner(),
                worktreesRoot = { scratch },
                resolveApiKey = { System.getenv("ANTHROPIC_API_KEY") }, // null → OAuth subscription
                registry = registry,
                router = MediationRouter(registry, hub),
                turnQueue = SessionTurnQueue(),
                scope = scope,
                allowedTools = listOf("Bash"), // tight: only Bash, to run the echo
            )
            val session = connector.open("backend")
            val events = CopyOnWriteArrayList<StreamJsonEvent>()
            val sub = scope.launch { session.events.collect { events.add(it) } }

            try {
                withTimeout(180_000) {
                    session.sendTurn(
                        UserTurn(
                            "Use the Bash tool to run exactly: echo $sentinel — " +
                                "then reply with exactly that command's output and nothing else.",
                        ),
                    )
                }
                delay(400)

                // --- Egress 1: /ws/agent event stream (tool_use.input + tool_result.content + text) ---
                val uiDump = events.joinToString("\n") { CommJson.encodeToString(StreamJsonEvent.serializer(), it) }
                val toolUse = events.filterIsInstance<AssistantEvent>()
                    .flatMap { it.message.content }.filterIsInstance<ToolUseBlock>()
                val toolResult = events.filterIsInstance<UserEvent>()
                    .flatMap { it.message.content }.filterIsInstance<ToolResultBlock>()
                assertTrue(toolUse.isNotEmpty(), "expected a tool_use (Bash) event")
                assertFalse(uiDump.contains(rawNeedle), "raw secret must not appear in any /ws/agent frame")
                assertTrue(uiDump.contains(SecretMasker.REDACTED), "events should carry the redaction marker")

                // --- Egress 2: mediated hub post ---
                val posted = hub.channelMessages("po", "po-backend")
                assertTrue(posted.isNotEmpty(), "result should be mediated to po-backend")
                assertFalse(posted.any { it.body.contains(rawNeedle) }, "raw secret must not appear in the hub post")

                // --- Egress 3: audit log ---
                val auditLines = auditCapture.list.map { it.formattedMessage }
                assertTrue(auditLines.any { it.contains("posted") }, "expected a posted audit line")
                assertFalse(auditLines.any { it.contains(rawNeedle) }, "raw secret must not appear in audit log")

                println("=== S8 MASKING COVERAGE (auth=OAuth-subscription, not D3) ===")
                println("tool_use.input   : ${toolUse.firstOrNull()?.input}")
                println("tool_result      : ${toolResult.firstOrNull()?.content}")
                println("hub po-backend   : ${posted.map { it.body }}")
                println("audit (posted)   : ${auditLines.filter { it.contains("posted") }}")
                println("raw needle present anywhere? ui=${uiDump.contains(rawNeedle)} hub=${posted.any { it.body.contains(rawNeedle) }} audit=${auditLines.any { it.contains(rawNeedle) }}")
            } finally {
                sub.cancel()
                session.close()
                scope.cancel()
                auditLogger.detachAppender(auditCapture)
                scratch.deleteRecursively()
            }
        }
    }
}
