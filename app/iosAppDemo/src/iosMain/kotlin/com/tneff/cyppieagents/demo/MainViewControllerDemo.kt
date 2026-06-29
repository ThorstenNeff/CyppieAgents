package com.tneff.cyppieagents.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.tneff.cyppieagents.acl.AclPanel
import com.tneff.cyppieagents.acl.AclViewModel
import com.tneff.cyppieagents.acl.StubAclHub
import com.tneff.cyppieagents.crossproject.CrossProjectControls
import com.tneff.cyppieagents.crossproject.CrossProjectViewModel
import com.tneff.cyppieagents.eventlog.EventBrowsePanel
import com.tneff.cyppieagents.eventlog.EventBrowseViewModel
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventTailPanel
import com.tneff.cyppieagents.eventlog.EventTailViewModel
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.project.ProjectSwitcherBar
import com.tneff.cyppieagents.project.ProjectViewModel
import com.tneff.cyppieagents.settings.SettingsPanel
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DEDICATED test/demo iOS entry — NOT the prod [com.tneff.cyppieagents.MainViewController] /
 * [com.tneff.cyppieagents.App]. The iOS mirror of [:app:androidAppDemo]'s `EventLogDemoApp`: a simple
 * tab switcher (`demo.tab.*`) over the multi-project + observability surfaces fed by fully STUB sources
 * ([DemoScenario], CYP-116), so the Maestro-iOS flows can exercise them WITHOUT a live server.
 *
 * Lives in the SEPARATE [:app:iosAppDemo] module → its OWN `SharedDemo.framework`, never the prod
 * `Shared.framework`. The prod `:app:iosApp` keeps `operatorToken = null` (operator surfaces omitted)
 * and is exercised by the presence/omission flow; this demo does not touch that path. NOT a
 * prod-flippable switch (PO guardrail 2026-06-27, CYP-69/CYP-116).
 *
 * Consumed by `iosAppDemo.xcodeproj` (bundle `com.tneff.cyppieagents.demo`) via
 * `MainViewControllerDemoKt.MainViewControllerDemo()`.
 */
fun MainViewControllerDemo() = ComposeUIViewController { EventLogDemoApp() }

/** Demo panel selector — which surface the [EventLogDemoApp] tab switcher shows. */
private enum class DemoPanel { BROWSE, TAIL, ACL, PAGER, PROJECT_SWITCHER, SETTINGS, CROSS_PROJECT }

private const val DEMO_TAB_BROWSE = "demo.tab.browse"
private const val DEMO_TAB_TAIL = "demo.tab.tail"
private const val DEMO_TAB_ACL = "demo.tab.acl"
private const val DEMO_TAB_PAGER = "demo.tab.pager"
private const val DEMO_TAB_PROJECT_SWITCHER = "demo.tab.projectSwitcher"
private const val DEMO_TAB_SETTINGS = "demo.tab.settings"
private const val DEMO_TAB_CROSS_PROJECT = "demo.tab.crossProject"

@Composable
fun EventLogDemoApp() {
    MaterialTheme {
        // enableTestTagsAsResourceId() at the root mirrors the android/web demo wiring. On iOS it is a
        // no-op (Compose iOS bridges testTag → accessibilityIdentifier natively, verified CYP-69 ②), so
        // the panels' testTags + the demo.tab.* tags are addressable for the Maestro `id:` selectors.
        Column(modifier = Modifier.enableTestTagsAsResourceId().safeContentPadding().fillMaxSize()) {
            var panel by remember { mutableStateOf(DemoPanel.BROWSE) }
            // 7 tabs > phone width → horizontally scrollable so every demo.tab.* stays reachable.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { panel = DemoPanel.BROWSE }, modifier = Modifier.testTag(DEMO_TAB_BROWSE)) { Text("Browse") }
                Button(onClick = { panel = DemoPanel.TAIL }, modifier = Modifier.testTag(DEMO_TAB_TAIL)) { Text("Live-Tail") }
                Button(onClick = { panel = DemoPanel.ACL }, modifier = Modifier.testTag(DEMO_TAB_ACL)) { Text("ACL") }
                Button(onClick = { panel = DemoPanel.PAGER }, modifier = Modifier.testTag(DEMO_TAB_PAGER)) { Text("Pager") }
                Button(onClick = { panel = DemoPanel.PROJECT_SWITCHER }, modifier = Modifier.testTag(DEMO_TAB_PROJECT_SWITCHER)) { Text("Projects") }
                Button(onClick = { panel = DemoPanel.SETTINGS }, modifier = Modifier.testTag(DEMO_TAB_SETTINGS)) { Text("Settings") }
                Button(onClick = { panel = DemoPanel.CROSS_PROJECT }, modifier = Modifier.testTag(DEMO_TAB_CROSS_PROJECT)) { Text("Cross-Project") }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Injected scope (not viewModelScope) so the demo VMs run without a ViewModelStoreOwner.
                val scope = rememberCoroutineScope()
                when (panel) {
                    DemoPanel.BROWSE -> {
                        // CYP-116: 2-project events + projects/active → cross-project view + project filter exercised.
                        val vm = remember { EventBrowseViewModel(DemoScenario.eventsApi(), scope = scope) }
                        EventBrowsePanel(vm, projects = DemoScenario.projects, activeProjectId = DemoScenario.ACTIVE_PROJECT)
                    }
                    DemoPanel.TAIL -> {
                        val vm = remember { EventTailViewModel(SteadyDemoEventsSource(), scope = scope) }
                        EventTailPanel(vm, projects = DemoScenario.projects, activeProjectId = DemoScenario.ACTIVE_PROJECT)
                    }
                    DemoPanel.ACL -> {
                        // Operator context (editable = true). rejectPoLockout = true so the demo mirrors
                        // the CYP-49 server guard → the Maestro flow can see the real 409/protected path.
                        val hub = remember { StubAclHub(rejectPoLockout = true) }
                        val vm = remember { AclViewModel(hub, hub, editable = true, scope = scope) }
                        AclPanel(vm)
                    }
                    DemoPanel.PAGER -> PhonePagerDemo()
                    DemoPanel.PROJECT_SWITCHER -> {
                        // CYP-116: 2-project switcher bar (menu → dropdown → Manage projects overlay).
                        val vm = remember { ProjectViewModel(DemoScenario.projectRepository(), editable = true, scope = scope) }
                        ProjectSwitcherBar(vm)
                    }
                    DemoPanel.SETTINGS -> {
                        val vm = remember { SettingsViewModel(DemoScenario.configRepository(), editable = true, scope = scope) }
                        SettingsPanel(vm)
                    }
                    DemoPanel.CROSS_PROJECT -> {
                        // CYP-116: the one shared channel's cross-project controls (status/authorize/revoke +
                        // the share dialog naming the target projects).
                        val vm = remember {
                            CrossProjectViewModel(
                                DemoScenario.crossProjectRepository(),
                                channelId = DemoScenario.CROSS_CHANNEL,
                                editable = true,
                                scope = scope,
                            )
                        }
                        CrossProjectControls(vm, targetProjects = DemoScenario.targetProjects)
                    }
                }
            }
        }
    }
}

/**
 * CYP-50 / S10 device harness: mounts the **real** [WindowHost] with stub windows + stub content, so
 * the iOS-Maestro phone-pager flow exercises the pager on a real phone viewport. On a phone the
 * [WindowHost] measures `Compact` width → it renders the `HorizontalPager` (snap, `phonePager.*` tags),
 * not the canvas — exactly the seam under test. Mirrors :app:androidAppDemo's PhonePagerDemo.
 */
@Composable
private fun PhonePagerDemo() {
    val state = remember {
        WindowManagerState(
            listOf(
                WindowState("po", "Product Owner", 0f, 0f, 200f, 150f),
                WindowState("frontend", "Frontend", 0f, 0f, 200f, 150f),
                WindowState("backend", "Backend", 0f, 0f, 200f, 150f),
                WindowState("comm", "Kommunikation", 0f, 0f, 200f, 150f),
            ),
        )
    }
    WindowHost(
        state = state,
        modifier = Modifier.fillMaxSize(),
        windowContent = { window ->
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Stub-Inhalt: ${window.title}")
            }
        },
    )
}

/**
 * A live source that keeps streaming at a steady cadence, so the Live-Tail buffered-count pill appears
 * when the Maestro flow pauses mid-stream. Demo-only; mirrors :app:androidAppDemo's source.
 */
private class SteadyDemoEventsSource(private val stepMillis: Long = 800L) : EventLiveSource {
    override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
        emit(EventLiveEvent.Connected)
        val types = listOf(
            EventType.TURN_START, EventType.TOOL_CALL, EventType.TOOL_RESULT,
            EventType.CONTEXT_USAGE, EventType.RESULT_FINAL,
        )
        var seq = 1L
        while (true) {
            delay(stepMillis)
            emit(
                EventLiveEvent.Received(
                    Event(
                        id = "d$seq", ts = 1_000 + seq, seq = seq, agentId = "backend",
                        projectId = if (seq % 2L == 0L) DemoScenario.PROJECT_B else DemoScenario.PROJECT_A,
                        type = types[((seq - 1) % types.size).toInt()], severity = Severity.INFO,
                        correlationId = "run-1", sessionId = "sess-1",
                    ),
                ),
            )
            seq++
        }
    }
}
