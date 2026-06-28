package com.tneff.cyppieagents.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

/** Demo panel selector — which surface the [EventLogDemoApp] tab switcher shows. */
private enum class DemoPanel { BROWSE, TAIL, ACL, PAGER }

private const val DEMO_TAB_BROWSE = "demo.tab.browse"
private const val DEMO_TAB_TAIL = "demo.tab.tail"
private const val DEMO_TAB_ACL = "demo.tab.acl"
private const val DEMO_TAB_PAGER = "demo.tab.pager"

/**
 * DEDICATED test/demo entry — NOT the prod [com.tneff.cyppieagents.MainActivity] / [com.tneff.cyppieagents.App].
 * Renders the operator-only Event-Log surfaces (Browse + Live-Tail) fed by fully STUB sources, so the
 * Maestro-android flows (`maestro/eventlog-*-android.yaml`) can exercise them WITHOUT a live server.
 *
 * **Why this is NOT the desktop `AgentShell`:** the shell tiles N floating windows in a
 * `ceil(sqrt(N))`-column grid positioned by `graphicsLayer` translation with a fixed, un-clipped
 * `.size()`. In an operator context that is ≥5 windows → a 3-column grid, and on a phone viewport
 * (~411dp) the 3rd column tiles off the visible right edge / the panel header overflows the ~160dp
 * tile — so the Live-Tail header children (live ●, filter chips) were unreachable to Maestro even
 * though the VM was correctly LIVE (CYP-47 P1-B; the shell-on-phone fit is a separate responsive
 * concern, CYP-26 territory). The demo therefore renders the panel **full-screen** via a simple tab
 * switcher: at full width the header fits one line and every child is on-screen. The panels and their
 * testTags are identical to the shell-hosted path.
 *
 * Non-productive by construction: stub sources that talk to no backend, in a SEPARATE artifact
 * (`:app:androidAppDemo`, applicationId `com.tneff.cyppieagents.demo`) that is never the prod build.
 * The operator-gated window omission stays a real security boundary on the prod side — the prod
 * `:app:androidApp` keeps `operatorToken = null` and is exercised by `eventlog-presence-android.yaml`;
 * this demo does not touch that path. NOT a prod-flippable switch (PO guardrail 2026-06-27).
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EventLogDemoApp() }
    }
}

@Composable
fun EventLogDemoApp() {
    MaterialTheme {
        // enableTestTagsAsResourceId() at the root exposes the testTags below (and inside the panels)
        // as Android view resource-ids for the Maestro `id:` selectors (CYP-15).
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
 * the Android-Maestro flow (`maestro/phone-pager-android.yaml`) exercises the phone-pager on a real
 * phone viewport. On a phone the [WindowHost] measures `Compact` width → it renders the
 * `HorizontalPager` (snap, `phonePager.*` tags), not the canvas — i.e. exactly the seam under test.
 *
 * The window CONTENT is irrelevant to the pager mechanics (mode switch, snap, indicator, page tags),
 * so it is a trivial stub — hermetic, no live server, no panel VMs.
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
 * when the Maestro flow pauses mid-stream. Demo-only; the real `EventsWsClient` (CYP-40) replaces it.
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
                        id = "d$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", teamId = "team-1",
                        type = types[((seq - 1) % types.size).toInt()], severity = Severity.INFO,
                        correlationId = "run-1", sessionId = "sess-1",
                    ),
                ),
            )
            seq++
        }
    }
}
