package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-277 — the two previously-unlabelled clickable EventBrowse controls now carry a contentDescription that
 * announces their (invisible) tap action to a screen reader. Uses the CYP-271 deterministic harness (the load
 * runs synchronously on [Dispatchers.Unconfined] so only `waitForIdle()` barriers are needed).
 *
 * Mutation proof: drop the `.semantics { contentDescription = … }` on the chip / drilldown header →
 * the matching assertion REDs (no such contentDescription).
 */
@OptIn(ExperimentalTestApi::class)
class EventBrowseA11yTest {

    private fun browseVm() = EventBrowseViewModel(StubEventsApi(), scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun filterChip_hasContentDescription_announcingTheTapAction() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { EventBrowsePanel(browseVm()) } } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.FILTER_SEVERITY, useUnmergedTree = true)
            .assertContentDescriptionContains("tap to change", substring = true)
    }

    @Test
    fun drilldownHeader_hasContentDescription_announcingTheClearAction() = runComposeUiTest {
        setContent { MaterialTheme { EventBrowsePanel(browseVm()) } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DRILLDOWN_HEADER, useUnmergedTree = true)
            .assertContentDescriptionContains("tap to clear", substring = true)
    }
}
