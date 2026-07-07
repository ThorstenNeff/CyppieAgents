package com.tneff.cyppieagents.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-288 (UIUX §10 fold, PO-Assistent Nit 1) — a CLASS-WIDE standing guard: the shared [LoadErrorRetry] MUST
 * carry `liveRegion = Polite` so a load error announces on APPEARANCE (not just on navigation) for a waiting
 * screen-reader user. Because all four REST panels (EventBrowse/Comm/AgentManagement/ACL) render this ONE
 * component, this single assert secures the a11y property for all of them — a future refactor that strips the
 * Polite (e.g. a modifier reorder) REDs here instead of silently regressing accessibility everywhere.
 */
@OptIn(ExperimentalTestApi::class)
class LoadErrorRetryA11yTest {

    @Test
    fun loadErrorRetry_carriesPoliteLiveRegion() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoadErrorRetry(message = "load failed", onRetry = {}, containerTag = "test.error", retryTag = "test.error.retry")
            }
        }
        val node = onNodeWithTag("test.error").fetchSemanticsNode()
        assertEquals(
            LiveRegionMode.Polite,
            node.config.getOrNull(SemanticsProperties.LiveRegion),
            "the shared LoadErrorRetry must announce Polite on appearance (secures the fold for all 4 panels)",
        )
    }
}
