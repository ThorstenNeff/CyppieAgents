package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-387 layer A — the wiring: [AgentViewModel.onSend] is the sole sent-message choke point, so it is where
 * the recall history is fed. Proves the hook, not the store (the store is [Cyp387InputHistoryTest]).
 */
class Cyp387ComposerHistoryRecordTest {

    private fun vm() = AgentViewModel(StubAgentSession(), agentId = "backend")

    /** A sent message lands in the recall snapshot, newest last.
     *  Mutation: remove the `inputHistory.record(trimmed)` call in onSend ⇒ snapshot stays empty ⇒ red. */
    @Test
    fun onSend_recordsSentMessagesForRecall() {
        val vm = vm()
        vm.onSend("erste nachricht")
        vm.onSend("zweite nachricht")
        assertEquals(listOf("erste nachricht", "zweite nachricht"), vm.inputHistorySnapshot())
    }

    /** A blank send is neither transmitted nor recorded (onSend returns early).
     *  Mutation: move the record above the empty-check ⇒ a blank entry appears ⇒ red. */
    @Test
    fun onSend_blankIsNotRecorded() {
        val vm = vm()
        vm.onSend("   ")
        vm.onSend("")
        assertTrue(vm.inputHistorySnapshot().isEmpty())
    }

    /** Trimmed form is recorded (recall replays what was actually sent).
     *  Mutation: record raw text ⇒ padding survives ⇒ red. */
    @Test
    fun onSend_recordsTrimmedForm() {
        val vm = vm()
        vm.onSend("  hallo  ")
        assertEquals(listOf("hallo"), vm.inputHistorySnapshot())
    }
}
