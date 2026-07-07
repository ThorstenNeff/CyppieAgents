package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.EventPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-288 A1 — a FAILED first-page load must render the honest error+retry surface (shared [LoadErrorRetry]),
 * NOT the "no events" empty state (the Sweep-#4 class-A gap: `EventBrowseViewModel` set `error` but the panel
 * only rendered `event_empty`, and the string didn't even exist). Error beats empty; Retry re-invokes the load.
 *
 * Mutation proof: revert the panel to `if (events.isEmpty() && !loading)` (drop the error branch) →
 * [firstPageLoadFailure_showsErrorAndRetry_notEmpty] REDs (EMPTY shows, ERROR absent). The empty-vs-error
 * contrast ([genuinelyEmpty_showsEmpty_notError]) keeps the branch non-vacuous.
 */
@OptIn(ExperimentalTestApi::class)
class EventBrowseErrorSurfaceTest {

    /** Every query fails → the VM sets `error` with no rows (the first-page-failure case). */
    private class FailingEventsApi : EventsApi {
        override suspend fun query(filter: EventFilter, page: Page): EventPage = throw RuntimeException("boom")
    }

    /** Fails once (→ error), then succeeds with the sample rows → proves Retry re-invokes the load. */
    private class FlakyEventsApi : EventsApi {
        var calls = 0
        private val stub = StubEventsApi()
        override suspend fun query(filter: EventFilter, page: Page): EventPage {
            calls += 1
            if (calls == 1) throw RuntimeException("boom")
            return stub.query(filter, page)
        }
    }

    /** Unconfined → the load runs synchronously at construction, so the panel composes with the outcome present. */
    private fun vm(api: EventsApi) = EventBrowseViewModel(api, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun firstPageLoadFailure_showsErrorAndRetry_notEmpty() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { EventBrowsePanel(vm(FailingEventsApi())) } } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.ERROR).assertExists()
        onNodeWithTag(EventBrowseTags.ERROR_RETRY).assertExists()
        onNodeWithTag(EventBrowseTags.EMPTY).assertDoesNotExist() // error beats empty — the fix
        onNodeWithTag(EventBrowseTags.TABLE).assertDoesNotExist()
    }

    @Test
    fun genuinelyEmpty_showsEmpty_notError() = runComposeUiTest {
        // Non-vacuous contrast: a SUCCESSFUL empty load still shows the plain empty state, never the error surface.
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { EventBrowsePanel(vm(StubEventsApi(emptyList()))) } } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.EMPTY).assertExists()
        onNodeWithTag(EventBrowseTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun retry_reinvokesLoad_recoversTable() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { EventBrowsePanel(vm(FlakyEventsApi())) } } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.ERROR).assertExists()
        onNodeWithTag(EventBrowseTags.ERROR_RETRY).performClick() // re-invokes the load; the 2nd query succeeds
        waitForIdle()
        onNodeWithTag(EventBrowseTags.TABLE).assertExists()
        onNodeWithTag(EventBrowseTags.ERROR).assertDoesNotExist()
    }
}
