package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.CreateChannelRequest
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test

/**
 * CYP-883 (OS-C) — ChannelManagementPanel render teeth (mirror of web-ts `ChannelManagementPanel.render.test.tsx`).
 * The ones that must BITE: 409-protected-HUB + 403-operator shown HONESTLY (server-authoritative, never silent) and
 * optimistic-rollback-on-reject (a rejected mutation reverts the UI — no hanging phantom). render ≠ authority.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp883ChannelMgmtPanelRenderTest {

    private val grp = Channel("g", "Grp", ChannelKind.GROUP, listOf("po"))
    private val hub = Channel("h", "Hub", ChannelKind.HUB, listOf("po"))
    private val agents = listOf("frontend", "backend")

    private class FakeApi(
        val createFn: suspend (CreateChannelRequest) -> Channel = { r -> Channel(r.id, r.name, r.kind, r.members.map { it.agentId }) },
        val renameFn: suspend (String, String) -> Channel = { id, name -> Channel(id, name, ChannelKind.GROUP, emptyList()) },
        val archiveFn: suspend (String) -> Unit = {},
    ) : ChannelMgmtApi {
        override suspend fun create(req: CreateChannelRequest): Channel = createFn(req)
        override suspend fun rename(channelId: String, name: String): Channel = renameFn(channelId, name)
        override suspend fun archive(channelId: String) = archiveFn(channelId)
    }

    @Test
    fun archiveReject_409_showsProtectedHubHonestly_andRollsBackTheRow() = runComposeUiTest {
        val api = FakeApi(archiveFn = { throw CommHttpException(409, "") })
        setContent { MaterialTheme { ChannelManagementPanel(listOf(grp), agents, operator = true, api = api) } }
        onNodeWithTag(ChannelMgmtTags.archive("g")).performClick()
        waitForIdle()
        // Honest, server-authoritative reason — never swallowed.
        onNodeWithTag(ChannelMgmtTags.ERROR).assert(SemanticsMatcher.expectValue(ChannelMutationReasonKey, "PROTECTED_HUB"))
        // ROLLBACK — the optimistically-hidden channel is restored (no phantom removal).
        onNodeWithTag(ChannelMgmtTags.row("g")).assertExists()
    }

    @Test
    fun archiveReject_403_showsOperatorOnlyHonestly() = runComposeUiTest {
        val api = FakeApi(archiveFn = { throw CommHttpException(403, "") })
        setContent { MaterialTheme { ChannelManagementPanel(listOf(grp), agents, operator = true, api = api) } }
        onNodeWithTag(ChannelMgmtTags.archive("g")).performClick()
        waitForIdle()
        onNodeWithTag(ChannelMgmtTags.ERROR).assert(SemanticsMatcher.expectValue(ChannelMutationReasonKey, "OPERATOR_ONLY"))
    }

    @Test
    fun create_isOptimistic_newRowAppearsBeforeServerConfirms() = runComposeUiTest {
        val api = FakeApi(createFn = { awaitCancellation() }) // never settles
        setContent { MaterialTheme { ChannelManagementPanel(listOf(grp), agents, operator = true, api = api) } }
        onNodeWithTag(ChannelMgmtTags.CREATE_ID).performTextInput("newc")
        onNodeWithTag(ChannelMgmtTags.CREATE_NAME).performTextInput("New C")
        onNodeWithTag(ChannelMgmtTags.createMember("frontend")).performClick()
        onNodeWithTag(ChannelMgmtTags.CREATE_SUBMIT).performClick()
        waitForIdle()
        onNodeWithTag(ChannelMgmtTags.row("newc")).assertExists() // shown optimistically, before resolve
    }

    @Test
    fun createReject_rollsBackTheOptimisticRow_andShowsError() = runComposeUiTest {
        val api = FakeApi(createFn = { throw CommHttpException(500, "") })
        setContent { MaterialTheme { ChannelManagementPanel(listOf(grp), agents, operator = true, api = api) } }
        onNodeWithTag(ChannelMgmtTags.CREATE_ID).performTextInput("newc")
        onNodeWithTag(ChannelMgmtTags.CREATE_NAME).performTextInput("New C")
        onNodeWithTag(ChannelMgmtTags.createMember("frontend")).performClick()
        onNodeWithTag(ChannelMgmtTags.CREATE_SUBMIT).performClick()
        waitForIdle()
        onNodeWithTag(ChannelMgmtTags.row("newc")).assertDoesNotExist() // rolled back — the phantom is gone
        onNodeWithTag(ChannelMgmtTags.ERROR).assert(SemanticsMatcher.expectValue(ChannelMutationReasonKey, "GENERIC"))
    }

    @Test
    fun hubArchive_isDisabled_clientHint_serverIsTheAuthoritativeGuard() = runComposeUiTest {
        setContent { MaterialTheme { ChannelManagementPanel(listOf(hub), agents, operator = true, api = FakeApi()) } }
        onNodeWithTag(ChannelMgmtTags.archive("h")).assertIsNotEnabled()
    }

    @Test
    fun nonOperator_cannotCreate_affordancesDisabled() = runComposeUiTest {
        setContent { MaterialTheme { ChannelManagementPanel(listOf(grp), agents, operator = false, api = FakeApi()) } }
        onNodeWithTag(ChannelMgmtTags.CREATE_SUBMIT).assertIsNotEnabled()
        onNodeWithTag(ChannelMgmtTags.CREATE_ID).assertIsNotEnabled()
    }
}
