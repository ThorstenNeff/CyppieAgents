package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-385 — the connection-loss notice must be flagged as an ERROR (so NoticeRow gives it the distinct `error`
 * tone, not the neutral one that reads like "alles ok").
 *
 * Emitted by [AgentViewModel] when the agent stream fails fatally; reproduced with the same throwing-session
 * harness as [AgentClientStampTest.connErrorNotice_alsoCarriesTheCurrentClientTime]. Mutation: drop `isError = true`
 * at the conn-error emission site ⇒ red.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp385ConnLostNoticeIsErrorTest {

    @Test
    fun connectionLostNotice_isFlaggedError() {
        val failing = object : AgentSession {
            override val events: Flow<AgentEvent> = kotlinx.coroutines.flow.flow { throw IllegalStateException("fatal") }
            override fun sendMessage(text: String) {}
        }
        runComposeUiTest {
            lateinit var vm: AgentViewModel
            setContent {
                MaterialTheme {
                    vm = remember { AgentViewModel(failing, "backend", nowMs = { 1_000L }) }
                    AgentWindow(agentId = "backend", viewModel = vm)
                }
            }
            waitUntil(timeoutMillis = 5_000L) { vm.transcript.value.isNotEmpty() }
            val notice = vm.transcript.value.single() as AgentEvent.Notice
            assertTrue(
                notice.isError,
                "the connection-loss notice must be flagged isError so it renders in the error tone (CYP-385) — " +
                    "a neutral 'Verbindung verloren' reads like nothing is wrong",
            )
        }
    }
}
