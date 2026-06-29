package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/**
 * CYP-94 render gate: the project filter axis is always present (operator-only surface); the cross-project
 * view indicator + per-row project identity appear ONLY in the cross-project view (a non-null lens), so
 * foreign events are distinguishable and never mistaken for the active project's.
 */
@OptIn(ExperimentalTestApi::class)
class EventCrossProjectRenderTest {

    private val projects = listOf(Project("p1", "P1"), Project("p2", "P2"))

    private fun ev(seq: Long, project: String) = Event(
        id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", projectId = project,
        type = EventType.TURN_START, severity = Severity.INFO, correlationId = null, sessionId = null,
        detail = JsonObject(emptyMap()),
    )
    private val events = listOf(ev(1, "p1"), ev(2, "p2"))

    private fun browseVm(filter: EventFilter) =
        EventBrowseViewModel(StubEventsApi(events), initialFilter = filter, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun browse_default_hasProjectFilter_noCrossViewNorRowProject() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { browseVm(EventFilter()) }
                EventBrowsePanel(vm, modifier = Modifier.size(900.dp, 600.dp), projects = projects, activeProjectId = "p1")
            }
        }
        // Rows ARE rendered (sized) — so the absent per-row project is a real "hidden", not "no rows".
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(EventBrowseTags.row(0)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(EventBrowseTags.FILTER_PROJECT).assertExists()
        onNodeWithTag(EventBrowseTags.CROSS_PROJECT_VIEW).assertDoesNotExist() // forced-active default
        // Per-row project hidden in the non-cross view (unmerged: the row's clickable merges descendants).
        onNodeWithTag(EventBrowseTags.rowProject(0), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun browse_crossView_showsIndicator_andPerRowProject() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { browseVm(EventFilter(projectId = "p2")) }
                EventBrowsePanel(vm, modifier = Modifier.size(900.dp, 600.dp), projects = projects, activeProjectId = "p1")
            }
        }
        // The row's clickable merges descendants → query the per-row project tag on the unmerged tree.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(EventBrowseTags.rowProject(0), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(EventBrowseTags.CROSS_PROJECT_VIEW).assertExists()
        onNodeWithTag(EventBrowseTags.rowProject(0), useUnmergedTree = true).assertExists()
    }

    @Test
    fun tail_default_vs_crossView_indicatorGated() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val def = remember { EventTailViewModel(StubEventsSource(events, stepMillis = 0L), scope = CoroutineScope(Dispatchers.Unconfined)) }
                EventTailPanel(def, projects = projects, activeProjectId = "p1")
            }
        }
        onNodeWithTag(EventTailTags.FILTER_PROJECT).assertExists()
        onNodeWithTag(EventTailTags.CROSS_PROJECT_VIEW).assertDoesNotExist()
    }

    @Test
    fun tail_crossView_showsIndicator() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    EventTailViewModel(
                        StubEventsSource(events, stepMillis = 0L),
                        filter = EventFilter(projectId = "p2"),
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                }
                EventTailPanel(vm, projects = projects, activeProjectId = "p1")
            }
        }
        onNodeWithTag(EventTailTags.CROSS_PROJECT_VIEW).assertExists()
    }
}
