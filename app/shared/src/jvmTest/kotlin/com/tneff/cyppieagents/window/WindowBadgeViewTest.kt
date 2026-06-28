package com.tneff.cyppieagents.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test

/**
 * CYP-55: the badge view renders the right variant node per [WindowBadge], distinguishable by **form**
 * (number / severity symbol / triangle) with an a11y label naming the window — not colour alone. The
 * fail-closed "no source → no badge" is enforced at the call site (a `null` badge renders nothing); the
 * variant nodes here are the positive contract QA asserts the **absence** of when there is no source.
 */
@OptIn(ExperimentalTestApi::class)
class WindowBadgeViewTest {

    @Test
    fun count_rendersCountVariant_withNumberAndA11y() = runComposeUiTest {
        setContent { MaterialTheme { WindowBadgeView("comm", "Kommunikation", WindowBadge.Count(3)) } }
        onNodeWithTag(WindowBadgeTags.badge("comm")).assertExists()
        onNodeWithTag(WindowBadgeTags.count("comm")).assertExists()
        // a11y names the window + count (locale-robust on the window title + number).
        onNodeWithContentDescription("Kommunikation", substring = true).assertExists()
        onNodeWithContentDescription("3", substring = true).assertExists()
    }

    @Test
    fun count_overflow_collapsesToNinePlus() = runComposeUiTest {
        setContent { MaterialTheme { WindowBadgeView("comm", "Kommunikation", WindowBadge.Count(15)) } }
        // Layout-stable overflow text is locale-independent.
        onNodeWithTag(WindowBadgeTags.count("comm")).assertTextEquals("9+")
    }

    @Test
    fun severity_rendersSeverityVariant_symbolNotContent() = runComposeUiTest {
        setContent { MaterialTheme { WindowBadgeView("eventtail", "Live-Tail", WindowBadge.SeverityLevel(Severity.WARN)) } }
        onNodeWithTag(WindowBadgeTags.severity("eventtail")).assertExists()
        // Symbol only ("!"), never event content.
        onNodeWithTag(WindowBadgeTags.severity("eventtail")).assertTextEquals("!")
        onNodeWithContentDescription("Live-Tail", substring = true).assertExists()
    }

    @Test
    fun attention_rendersAttentionVariant_forAgentError() = runComposeUiTest {
        setContent { MaterialTheme { WindowBadgeView("frontend", "Frontend", WindowBadge.Attention) } }
        onNodeWithTag(WindowBadgeTags.attention("frontend")).assertExists()
        onNodeWithContentDescription("Frontend", substring = true).assertExists()
        // Not a count/severity variant.
        onNodeWithTag(WindowBadgeTags.count("frontend")).assertDoesNotExist()
        onNodeWithTag(WindowBadgeTags.severity("frontend")).assertDoesNotExist()
    }
}
