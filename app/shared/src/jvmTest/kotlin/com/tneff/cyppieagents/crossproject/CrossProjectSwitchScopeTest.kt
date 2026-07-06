package com.tneff.cyppieagents.crossproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.CommPanel
import com.tneff.cyppieagents.comm.CommViewModel
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-258 — is a channel's cross-project share badge STALE after a project switch? Suspicion (PO-Assistent, live
 * UNconfirmed): `AgentShell` keys the per-channel [CrossProjectViewModel] on `crossproject-$cid` (channel id only,
 * NOT `activeProjectId`), so a same-id channel that exists in two projects (the hub-and-spoke `po-<worker>`
 * channels ARE same-id per project) would render the PREVIOUS project's retained VM after a switch → stale badge.
 *
 * This reproduces the exact production wiring DETERMINISTICALLY — the reason the PO-Assistent's probe was flaky is
 * the badge only renders in the comm CHANNEL LIST, which is pane-mode-dependent (hidden on auto-select below
 * `PANE_COLLAPSE_WIDTH`); here [CommPanel] is wrapped WIDE (≥600 → two-pane → the channel-list badge always shows)
 * and the switch is driven by a plain [State] flip, so the async load + switch settle deterministically.
 *
 * Fidelity: the harness uses the REAL [CommPanel] LazyColumn nesting (the "nested scope" the PO flagged) + the REAL
 * [CrossProjectViewModel] + `AgentShell`'s exact keying expression (`crossproject-$cid` vs the project-scoped fix),
 * with `commVm` re-keyed per project exactly as CYP-246 does. `keyWithProject` toggles the buggy vs fixed key so a
 * single harness both reproduces the stale badge AND checks whether the project-scoped key suffices.
 */
@OptIn(ExperimentalTestApi::class)
class CrossProjectSwitchScopeTest {

    private val channelId = "po-frontend" // same id in BOTH projects (hub-and-spoke seeds po-<worker> per project)

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() =
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "Frontend", Role.WORKER, "frontend"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    /**
     * Cross-project status resolved server-side from the active project (like `/api/agents` — no client projectId):
     * SHARED in "alpha" (→ badge), NOT shared and not spanning in "beta" (→ no badge). The clean alpha=badge /
     * beta=no-badge signal makes stale (badge lingers) vs fresh (badge gone) unambiguous.
     */
    private class FakeCrossRepo(private val active: () -> String) : CrossProjectRepository {
        override suspend fun status(channelId: String): CrossShareStatus =
            if (active() == "alpha") {
                CrossShareStatus(
                    channelId, shared = true, sharedAt = 1000L,
                    reachableMembers = listOf(CrossMember("designer", "beta", CrossAccess.READ)),
                )
            } else {
                CrossShareStatus(channelId, shared = false)
            }
        override suspend fun authorize(channelId: String) = status(channelId)
        override suspend fun revoke(channelId: String) = status(channelId)
    }

    private val commApi = FakeCommApi()

    @Composable
    private fun Harness(active: State<String>, crossRepo: CrossProjectRepository, keyWithProject: Boolean) {
        val a = active.value
        Box(Modifier.size(820.dp, 600.dp)) { // wide → two-pane → the channel-list badge always renders (stable)
            // CYP-246: comm VM is re-keyed per project (fresh CommPanel composition on switch).
            val commVm = viewModel(key = "comm-$a") { CommViewModel(commApi, StubCommLiveSource(), viewerId = "operator") }
            CommPanel(
                commVm,
                crossProjectSlot = { cid ->
                    // AgentShell's exact keying expression — buggy `crossproject-$cid` vs the project-scoped fix.
                    val key = if (keyWithProject) "crossproject-$a-$cid" else "crossproject-$cid"
                    CrossProjectControls(viewModel(key = key) { CrossProjectViewModel(crossRepo, cid, editable = true) })
                },
            )
        }
    }

    /** Reproduction: the current `crossproject-$cid` key retains the alpha VM after switch → the shared badge is STALE. */
    @Test
    fun cidOnlyKey_badgeStaysStaleAfterSwitch() = runComposeUiTest {
        val active = mutableStateOf("alpha")
        val repo = FakeCrossRepo { active.value }
        setContent { MaterialTheme { Harness(active, repo, keyWithProject = false) } }

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(CrossProjectTags.badge(channelId)).fetchSemanticsNodes().isNotEmpty()
        }
        active.value = "beta"
        waitForIdle()
        // The retained (cid-only-keyed) VM never re-fetched → alpha's shared badge persists though beta isn't shared.
        onNodeWithTag(CrossProjectTags.badge(channelId)).assertExists()
    }

    /** Fix check: the project-scoped key hands back a fresh VM after switch → it re-fetches beta → the badge is GONE. */
    @Test
    fun projectScopedKey_badgeClearsAfterSwitch() = runComposeUiTest {
        val active = mutableStateOf("alpha")
        val repo = FakeCrossRepo { active.value }
        setContent { MaterialTheme { Harness(active, repo, keyWithProject = true) } }

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(CrossProjectTags.badge(channelId)).fetchSemanticsNodes().isNotEmpty()
        }
        active.value = "beta"
        waitForIdle()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(CrossProjectTags.badge(channelId)).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(CrossProjectTags.badge(channelId)).assertDoesNotExist()
    }
}
