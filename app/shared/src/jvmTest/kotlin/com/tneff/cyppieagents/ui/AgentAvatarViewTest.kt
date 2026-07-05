package com.tneff.cyppieagents.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-216 — the shared avatar's stages 3→4 (initials → colour). One place renders every identity avatar, so these
 * teeth (initials from name, id fallback, custom-colour path, the Agent overload) hold for all call sites at once.
 */
@OptIn(ExperimentalTestApi::class)
class AgentAvatarViewTest {

    @Test
    fun rendersInitials_fromDisplayName() = runComposeUiTest {
        setContent { MaterialTheme { AgentAvatarView(id = "backend", size = 28.dp, displayName = "Backend Dev") } }
        onNodeWithTag(AvatarTags.avatar("backend")).assertExists()
        onNodeWithText("BD").assertExists() // first+last initial
    }

    @Test
    fun fallsBackToId_whenDisplayNameBlank() = runComposeUiTest {
        setContent { MaterialTheme { AgentAvatarView(id = "backend", size = 22.dp, displayName = "") } }
        // Stage-3 fallback: a blank name → initials of the stable id (never an empty avatar).
        onNodeWithText("BA").assertExists()
    }

    @Test
    fun customColorHex_takesTheDerivedPath_withoutCrashing() = runComposeUiTest {
        setContent { MaterialTheme { AgentAvatarView(id = "x", size = 24.dp, displayName = "Foo", colorHex = "#3B82F6") } }
        onNodeWithTag(AvatarTags.avatar("x")).assertExists()
        onNodeWithText("FO").assertExists() // single word → first two letters
    }

    @Test
    fun agentOverload_pullsIdentityFromTheModel() = runComposeUiTest {
        val agent = Agent("frontend", "Frontend", Role.WORKER, "frontend")
        setContent { MaterialTheme { AgentAvatarView(agent, size = 28.dp) } }
        onNodeWithTag(AvatarTags.avatar("frontend")).assertExists()
        onNodeWithText("FR").assertExists()
    }
}
