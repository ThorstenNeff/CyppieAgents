package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-11: proves the Compose UI-test infrastructure works end-to-end on a runnable target (JVM),
 * covering the two things the iOS-tester flagged for the Maestro smoke (Test-Contract v0.5):
 *  1. a `testTag` is addressable, and
 *  2. `performScrollToNode` reaches an **off-screen** LazyColumn row (only composed/visible nodes
 *     exist, so the scroll-then-assert pattern is mandatory).
 *
 * Uses a self-contained harness (not the CYP-6 renderer) so this infra ticket stays independent of
 * unmerged feature code. Tags follow the v0.5 schema shape (`agent.<id>.…`).
 */
@OptIn(ExperimentalTestApi::class)
class UiTestInfraTest {

    @Test
    fun testTag_isAddressable_andOffScreenRowReachableByScroll() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                BasicTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.testTag("agent.demo.input"),
                )
                LazyColumn(Modifier.testTag("agent.demo.stream").fillMaxSize()) {
                    items((0 until 60).toList()) { i ->
                        Text("Zeile $i", Modifier.testTag("agent.demo.event.$i"))
                    }
                }
            }
        }

        // (1) tag addressable
        onNodeWithTag("agent.demo.input").assertIsDisplayed()

        // (2) an off-screen row is reachable only after scrolling its container to it
        onNodeWithTag("agent.demo.stream")
            .performScrollToNode(hasTestTag("agent.demo.event.50"))
        onNodeWithTag("agent.demo.event.50").assertIsDisplayed()
    }
}
