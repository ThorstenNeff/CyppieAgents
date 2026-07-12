package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-479 §4.1 — revoke honesty teeth (HF, guaranteed-vs-advisory). The confirm dialog always shows the
 * **scope note** ("no global revoke"); the **≤TTL hint is seam-gated** — absent unless a TTL is supplied
 * (`null≠0`), present when it is; confirming fires the **guaranteed teardown** and surfaces the "ended" receipt.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteRevokeControlRenderTest {

    @Test
    fun trigger_opensConfirm_withScopeNote_ttlAbsent() = runComposeUiTest {
        setContent { MaterialTheme { RemoteRevokeControl(onEndSession = {}) } } // ttl = null
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).performClick()
        onNodeWithTag(RemoteRevokeTags.CONFIRM, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRevokeTags.SCOPE_NOTE, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRevokeTags.TTL_HINT, useUnmergedTree = true).assertDoesNotExist() // seam-gated
    }

    @Test
    fun ttlHint_shownOnlyWhenProvided() = runComposeUiTest {
        setContent { MaterialTheme { RemoteRevokeControl(onEndSession = {}, ttl = "10 min") } }
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).performClick()
        onNodeWithTag(RemoteRevokeTags.TTL_HINT, useUnmergedTree = true).assertExists()
    }

    @Test
    fun confirm_firesTeardown_showsEnded() = runComposeUiTest {
        var ended = false
        setContent { MaterialTheme { RemoteRevokeControl(onEndSession = { ended = true }) } }
        onNodeWithTag(RemoteRevokeTags.END, useUnmergedTree = true).performClick()
        onNodeWithTag(RemoteRevokeTags.CONFIRM, useUnmergedTree = true).performClick()
        assertTrue(ended, "confirm fires the guaranteed local teardown")
        onNodeWithTag(RemoteRevokeTags.ENDED, useUnmergedTree = true).assertExists()
    }
}
