package com.tneff.cyppieagents.crossproject

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-93 render gate: the honest cross-project state (badge / unauthorized-qualifier / status), the
 * owner-gated authorize/revoke action, and the authorization dialog — whose confirm is gated behind the
 * deliberate owner-consent (the structural anti-injection guard: only the owner authorizes, via this
 * explicit step — never a message/agent). testTags exactly per `docs/design/cross-project-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class CrossProjectRenderTest {

    private val members = listOf(CrossMember("agent-b", "p2", CrossAccess.READ))

    private fun vm(editable: Boolean, shared: Boolean = false) = CrossProjectViewModel(
        StubCrossProjectRepository(
            reachByChannel = mapOf("c1" to members),
            initiallyShared = if (shared) setOf("c1") else emptySet(),
        ),
        channelId = "c1", editable = editable, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun unshared_crossProject_showsUnauthorizedBadge_andAuthorizeAction() = runComposeUiTest {
        setContent { MaterialTheme { val v = remember { vm(editable = true) }; CrossProjectControls(v) } }
        onNodeWithTag(CrossProjectTags.badgeUnauthorized("c1")).assertExists() // spans but not authorized (fail-closed)
        onNodeWithTag(CrossProjectTags.badge("c1")).assertDoesNotExist()
        onNodeWithTag(CrossProjectTags.STATUS).assertExists()
        onNodeWithTag(CrossProjectTags.member("agent-b")).assertExists() // concrete member home-project disclosure
        onNodeWithTag(CrossProjectTags.AUTHORIZE).assertExists()
        onNodeWithTag(CrossProjectTags.REVOKE).assertDoesNotExist()
    }

    @Test
    fun shared_showsBadge_andRevoke() = runComposeUiTest {
        setContent { MaterialTheme { val v = remember { vm(editable = true, shared = true) }; CrossProjectControls(v) } }
        onNodeWithTag(CrossProjectTags.badge("c1")).assertExists()
        onNodeWithTag(CrossProjectTags.REVOKE).assertExists()
        onNodeWithTag(CrossProjectTags.AUTHORIZE).assertDoesNotExist()
    }

    @Test
    fun noOperator_gateHint_noAction() = runComposeUiTest {
        setContent { MaterialTheme { val v = remember { vm(editable = false) }; CrossProjectControls(v) } }
        onNodeWithTag(CrossProjectTags.GATE_HINT).assertExists()
        onNodeWithTag(CrossProjectTags.AUTHORIZE).assertDoesNotExist()
        onNodeWithTag(CrossProjectTags.REVOKE).assertDoesNotExist()
    }

    @Test
    fun dialog_showsScope_humanOnly_singleOwner_confirmGatedByConsent() = runComposeUiTest {
        setContent { MaterialTheme { val v = remember { vm(editable = true) }; CrossProjectControls(v) } }
        onNodeWithTag(CrossProjectTags.AUTHORIZE).performClick()
        onNodeWithTag(CrossProjectTags.DIALOG).assertExists()
        onNodeWithTag(CrossProjectTags.DIALOG_SCOPE).assertExists()
        onNodeWithTag(CrossProjectTags.DIALOG_HUMAN_ONLY).assertExists() // anti-injection note visible (§2.6)
        onNodeWithTag(CrossProjectTags.DIALOG_SINGLE_OWNER).assertExists()
        // Confirm is gated behind the deliberate owner consent — only the owner authorizes, via this step.
        onNodeWithTag(CrossProjectTags.DIALOG_CONFIRM).assertIsNotEnabled()
        onNodeWithTag(CrossProjectTags.DIALOG_OWNER_CONSENT).performClick()
        onNodeWithTag(CrossProjectTags.DIALOG_CONFIRM).assertIsEnabled()
    }
}
