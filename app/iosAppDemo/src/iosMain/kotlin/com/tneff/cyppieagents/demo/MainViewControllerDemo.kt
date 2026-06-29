package com.tneff.cyppieagents.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
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
import com.tneff.cyppieagents.eventlog.EventBrowsePanel
import com.tneff.cyppieagents.eventlog.EventBrowseViewModel
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventTailPanel
import com.tneff.cyppieagents.eventlog.EventTailViewModel
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
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
 * tab switcher (`demo.tab.*`) over the Browse / Live-Tail / ACL / Phone-Pager surfaces fed by fully
 * STUB sources, so the Maestro-iOS flows can exercise them WITHOUT a live server.
 *
 * Lives in the SEPARATE [:app:iosAppDemo] module → its OWN `SharedDemo.framework`, never the prod
 * `Shared.framework`. The prod `:app:iosApp` keeps `operatorToken = null` (operator surfaces omitted)
 * and is exercised by the presence/omission flow; this demo does not touch that path. NOT a
 * prod-flippable switch (PO guardrail 2026-06-27, CYP-69).
 *
 * Consumed by `iosAppDemo.xcodeproj` (bundle `com.tneff.cyppieagents.demo`) via
 * `MainViewControllerDemoKt.MainViewControllerDemo()`.
 */
fun MainViewControllerDemo() = ComposeUIViewController { EventLogDemoApp() }

/** Demo panel selector — which surface the [EventLogDemoApp] tab switcher shows. */
private enum class DemoPanel { BROWSE, TAIL, ACL, PAGER }

private const val DEMO_TAB_BROWSE = "demo.tab.browse"
private const val DEMO_TAB_TAIL = "demo.tab.tail"
private const val DEMO_TAB_ACL = "demo.tab.acl"
private const val DEMO_TAB_PAGER = "demo.tab.pager"

@Composable
fun EventLogDemoApp() {
    MaterialTheme {
        // enableTestTagsAsResourceId() at the root mirrors the android/web demo wiring. On iOS it is a
        // no-op (Compose iOS bridges testTag → accessibilityIdentifier natively, verified CYP-69 ②), so
        // the panels' testTags + the demo.tab.* tags are addressable for the Maestro `id:` selectors.
        Column(modifier = Modifier.enableTestTagsAsResourceId().safeContentPadding().fillMaxSize()) {
            var panel by remember { mutableStateOf(DemoPanel.BROWSE) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { panel = DemoPanel.BROWSE }, modifier = Modifier.testTag(DEMO_TAB_BROWSE)) {
                    Text("Browse")
                }
                Button(onClick = { panel = DemoPanel.TAIL }, modifier = Modifier.testTag(DEMO_TAB_TAIL)) {
                    Text("Live-Tail")
                }
                Button(onClick = { panel = DemoPanel.ACL }, modifier = Modifier.testTag(DEMO_TAB_ACL)) {
                    Text("ACL")
                }
                Button(onClick = { panel = DemoPanel.PAGER }, modifier = Modifier.testTag(DEMO_TAB_PAGER)) {
                    Text("Pager")
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Injected scope (not viewModelScope) so the demo VMs run without a ViewModelStoreOwner.
                val scope = rememberCoroutineScope()
                when (panel) {
                    DemoPanel.BROWSE -> {
                        val vm = remember { EventBrowseViewModel(StubEventsApi(), scope = scope) }
                        EventBrowsePanel(vm)
                    }
                    DemoPanel.TAIL -> {
                        val vm = remember { EventTailViewModel(SteadyDemoEventsSource(), scope = scope) }
                        EventTailPanel(vm)
                    }
                    DemoPanel.ACL -> {
                        // Operator context (editable = true). rejectPoLockout = true so the demo mirrors
                        // the CYP-49 server guard → the Maestro flow can see the real 409/protected path.
                        val hub = remember { StubAclHub(rejectPoLockout = true) }
                        val vm = remember { AclViewModel(hub, hub, editable = true, scope = scope) }
                        AclPanel(vm)
                    }
                    DemoPanel.PAGER -> PhonePagerDemo()
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
                        id = "d$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", projectId = "team-1",
                        type = types[((seq - 1) % types.size).toInt()], severity = Severity.INFO,
                        correlationId = "run-1", sessionId = "sess-1",
                    ),
                ),
            )
            seq++
        }
    }
}
