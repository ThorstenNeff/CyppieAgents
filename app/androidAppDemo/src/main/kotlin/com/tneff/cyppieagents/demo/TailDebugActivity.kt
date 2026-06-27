package com.tneff.cyppieagents.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventTailPanel
import com.tneff.cyppieagents.eventlog.EventTailViewModel
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CYP-47 DEBUG harness — **temporary**, remove after the Android Live-Tail root-cause.
 *
 * Hard device evidence (Android-Tester) showed `liveIndicator` is NOT composed (not clipped) → `state.connection`
 * never reaches LIVE on real Android-Compose, although Received events flow. JVM `runComposeUiTest` does NOT
 * reproduce it. This activity renders the REAL [EventTailPanel] + [EventTailViewModel] with three log probes so
 * the Pixel_9a run reveals exactly where the chain breaks (Logcat tag `CYP47TAIL`):
 *   [SRC]   — does the cold source actually emit Connected, and on which thread?
 *   [VM]    — does the VM receive it, what does statusOf return, and does connection update?
 *   [STATE] — does the panel's observed StateFlow ever carry connection=LIVE?
 *
 * Launch:  adb shell am start -n com.tneff.cyppieagents.demo/.TailDebugActivity
 */
class TailDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm = remember {
                    EventTailViewModel(
                        source = LoggingTailSource(),
                        debug = { msg -> Log.i(TAG, "[VM] $msg  thread=${Thread.currentThread().name}") },
                    )
                }
                LaunchedEffect(Unit) {
                    vm.state.collect { s ->
                        Log.i(TAG, "[STATE] connection=${s.connection} paused=${s.paused} events=${s.events.size} thread=${Thread.currentThread().name}")
                    }
                }
                EventTailPanel(vm, Modifier.enableTestTagsAsResourceId().safeContentPadding().fillMaxSize())
            }
        }
    }

    private companion object {
        const val TAG = "CYP47TAIL"
    }
}

/** Cold source that logs each emission (type + thread) so we see whether Connected reaches the VM on Android. */
private class LoggingTailSource : EventLiveSource {
    override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
        Log.i("CYP47TAIL", "[SRC] collect start thread=${Thread.currentThread().name}")
        emit(EventLiveEvent.Connected)
        Log.i("CYP47TAIL", "[SRC] emitted Connected thread=${Thread.currentThread().name}")
        var seq = 1L
        while (true) {
            delay(800)
            emit(
                EventLiveEvent.Received(
                    Event(
                        id = "d$seq", ts = seq, seq = seq, agentId = "backend", teamId = "t",
                        type = EventType.TURN_START, severity = Severity.INFO,
                    ),
                ),
            )
            Log.i("CYP47TAIL", "[SRC] emitted Received seq=$seq thread=${Thread.currentThread().name}")
            seq++
        }
    }
}
