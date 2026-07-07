package com.tneff.cyppieagents

import com.tneff.cyppieagents.agentview.AgentWsClient
import com.tneff.cyppieagents.comm.CommLiveEvent
import com.tneff.cyppieagents.comm.CommWsClient
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test

/**
 * Manual live-demo durchstich harness (NOT a CI test): connects the REAL client adapters
 * ([AgentWsClient] + [CommWsClient]) — the same ones the desktop shell uses — against the booted
 * server (localhost:8787) and prints a deterministic, **masked** log of the event flow + a turn.
 *
 * Skipped unless `DURCHSTICH=1`. Tokens come from the host env via [defaultShellConfig] and are
 * **never logged**; event payloads are already masked server-side (SecretMasker), and text is
 * additionally truncated here.
 *
 * Run (on "Server up"):
 *   DURCHSTICH=1 OPERATOR_TOKEN=… [HUB_TOKEN_BACKEND=…] ./gradlew :app:shared:jvmTest \
 *     --tests "com.tneff.cyppieagents.DurchstichHarness" --info
 */
class DurchstichHarness {

    @Test
    fun liveDurchstich() = runBlocking {
        if (System.getenv("DURCHSTICH") != "1") return@runBlocking // manual only — no-op in CI

        val cfg = defaultShellConfig()
        log("config: ${cfg.hubWsBaseUrl} (operatorToken=${present(cfg.operatorToken)})")
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            // (1) /ws/agent — live stream + a human turn to the backend agent.
            log("== /ws/agent backend ==")
            val agentWs = AgentWsClient(client, cfg.hubWsBaseUrl, "backend", cfg.agentToken("backend"))
            val agentJob = launch {
                agentWs.events.collect { log("agent: ${summarize(it)}") }
            }
            delay(2_000)
            log("--> sending turn")
            agentWs.send(UserTurn("Sag bitte kurz hallo und nenne dein Arbeitsverzeichnis."))
            delay(20_000) // observe system/init → assistant(thinking/text/tool_use) → result
            agentJob.cancel()

            // (2) /ws/comm — operator live view (ChannelsEvent snapshot → messages).
            log("== /ws/comm operator ==")
            val operator = cfg.operatorToken
            if (operator == null) {
                log("OPERATOR_TOKEN not set — skipping /ws/comm")
            } else {
                val commWs = CommWsClient(client, cfg.hubWsBaseUrl, operator)
                withTimeoutOrNull(10_000) {
                    commWs.events().collect { log("comm: ${summarize(it)}") }
                }
            }
            log("== durchstich done ==")
        } finally {
            client.close()
        }
    }

    private fun summarize(e: StreamJsonEvent): String = when (e) {
        is SystemEvent -> "system/${e.subtype} model=${e.model}"
        is AssistantEvent -> "assistant[" + e.message.content.joinToString { block ->
            when (block) {
                is TextBlock -> "text:'${trunc(block.text)}'"
                is ToolUseBlock -> "tool_use:${block.name}"
                else -> block::class.simpleName ?: "block"
            }
        } + "]"
        is UserEvent -> "user(replay/tool_result)"
        is ResultEvent -> "result is_error=${e.isError} subtype=${e.subtype}"
        else -> e::class.simpleName ?: "event"
    }

    private fun summarize(e: CommLiveEvent): String = when (e) {
        is CommLiveEvent.Connected -> "Connected"
        is CommLiveEvent.Disconnected -> "Disconnected"
        is CommLiveEvent.AccessRevoked -> "AccessRevoked"
        is CommLiveEvent.AclChanged -> "AclChanged"
        is CommLiveEvent.ChannelsChanged -> "ChannelsChanged(${e.channels.map { it.id }})"
        is CommLiveEvent.MessageReceived -> "Message(id=${e.message.id} from=${e.message.from} '${trunc(e.message.body)}')"
    }

    private fun trunc(s: String, max: Int = 60): String =
        s.replace('\n', ' ').let { if (it.length <= max) it else it.take(max - 1) + "…" }

    private fun present(s: String?): String = if (s.isNullOrEmpty()) "absent" else "present(masked)"

    private fun log(msg: String) = println("[durchstich] $msg")
}
