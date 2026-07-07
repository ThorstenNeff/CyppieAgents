package com.tneff.cyppieagents.crossproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-284 — the per-member "agentId · home-project" line sits in the hard 220dp comm channel column (two-pane).
 * A long agentId/home-project name must ellipsize to ONE line, not wrap/overflow the fixed-width column.
 *
 * Font-independent, non-vacuous tooth: a long-value member and a short-value member rendered in the same 220dp
 * column must have the SAME (single-line) height. Mutation proof: drop `maxLines = 1` → the long value wraps to
 * multiple lines → its height exceeds the short one's → the assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class CrossProjectMemberEllipsisTest {

    private val shortId = "a"
    private val longId = "a-deliberately-very-long-agent-identifier-that-would-overflow-the-fixed-220dp-column"

    private fun vm() = CrossProjectViewModel(
        StubCrossProjectRepository(
            reachByChannel = mapOf(
                "c1" to listOf(
                    CrossMember(shortId, "p", CrossAccess.READ),
                    CrossMember(longId, "and-a-long-home-project-name-as-well", CrossAccess.READ),
                ),
            ),
            initiallyShared = setOf("c1"),
        ),
        channelId = "c1", editable = true, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun longMemberValue_ellipsizesToOneLine_inThe220dpColumn() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(220.dp)) { CrossProjectControls(remember { vm() }) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CrossProjectTags.member(longId)).fetchSemanticsNodes().isNotEmpty() }
        val shortBounds = onNodeWithTag(CrossProjectTags.member(shortId)).getBoundsInRoot()
        val longBounds = onNodeWithTag(CrossProjectTags.member(longId)).getBoundsInRoot()
        val shortHeight = shortBounds.bottom - shortBounds.top
        val longHeight = longBounds.bottom - longBounds.top
        assertTrue(longHeight <= shortHeight + 4.dp, "long member ($longHeight) must stay single-line like the short one ($shortHeight)")
    }
}
