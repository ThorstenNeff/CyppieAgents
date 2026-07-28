package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-884 (OS-D) — AgentAddressPicker render teeth (mirror of web-ts `AgentAddressPicker.render.test.tsx`). Resolved →
 * DM enabled, selects the spoke; unreachable → honest hint, DM disabled, NO channel selected/fabricated; ambiguous →
 * a visible flag, DM disabled, NEVER a silent-first pick. render ≠ authority.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp884AgentAddressPickerRenderTest {

    private fun ch(id: String, kind: ChannelKind, members: List<String>) = Channel(id, id, kind, members)
    private val agents = listOf("frontend", "backend")

    @Test
    fun resolved_pickingAgentWithOneSpoke_enablesDm_selectsThatChannel() = runComposeUiTest {
        var selected: String? = null
        val spokeFE = ch("po-frontend", ChannelKind.DIRECT, listOf("po", "frontend"))
        setContent { MaterialTheme { AgentAddressPicker(agents, listOf(spokeFE), onSelectChannel = { selected = it }) } }
        onNodeWithTag(AgentAddressTags.option("frontend")).performClick()
        onNodeWithTag(AgentAddressTags.DM).performClick()
        assertEquals("po-frontend", selected)
    }

    @Test
    fun unreachable_noSpoke_honestHint_dmDisabled_noChannelSelected() = runComposeUiTest {
        var selected: String? = null
        setContent {
            MaterialTheme {
                AgentAddressPicker(agents, listOf(ch("team", ChannelKind.GROUP, listOf("po", "frontend"))), onSelectChannel = { selected = it })
            }
        }
        onNodeWithTag(AgentAddressTags.option("frontend")).performClick()
        onNodeWithTag(AgentAddressTags.UNREACHABLE).assertExists()
        onNodeWithTag(AgentAddressTags.DM).assertIsNotEnabled()
        onNodeWithTag(AgentAddressTags.DM).performClick() // disabled → no-op
        assertNull(selected) // NO channel selected/fabricated
        onNodeWithTag(AgentAddressTags.AMBIGUOUS).assertDoesNotExist()
    }

    @Test
    fun ambiguous_multipleSpokes_visibleFlag_dmDisabled_neverSilentFirst() = runComposeUiTest {
        var selected: String? = null
        val dupA = ch("po-frontend", ChannelKind.DIRECT, listOf("po", "frontend"))
        val dupB = ch("po-frontend-2", ChannelKind.DIRECT, listOf("po", "frontend"))
        setContent { MaterialTheme { AgentAddressPicker(agents, listOf(dupA, dupB), onSelectChannel = { selected = it }) } }
        onNodeWithTag(AgentAddressTags.option("frontend")).performClick()
        onNodeWithTag(AgentAddressTags.AMBIGUOUS).assertExists()
        onNodeWithTag(AgentAddressTags.DM).assertIsNotEnabled()
        onNodeWithTag(AgentAddressTags.DM).performClick() // disabled → no-op
        assertNull(selected) // no silent route from untrusted data
    }
}
