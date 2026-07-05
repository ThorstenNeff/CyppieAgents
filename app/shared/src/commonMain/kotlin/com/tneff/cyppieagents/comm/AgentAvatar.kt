package com.tneff.cyppieagents.comm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role

/** Stable test tags for the shared agent avatar (CYP-216). */
object AvatarTags {
    fun avatar(id: String): String = "avatar.$id"
}

/**
 * CYP-216 — the ONE shared agent avatar (the anti-divergence foundation for Epic CYP-212). Every identity avatar
 * (comm message rows, event log, …) renders through this, so the look + the fallback chain live in a single place
 * instead of being re-implemented per site.
 *
 * Fallback chain — this composable owns the LOWER stages **3 → 4 (initials → colour)**: the initials of [displayName]
 * on the resolved identity fill, wrapped in the CYP-209 contrast-safe colour **RING** ([SenderColor.borderColor], ≥3:1
 * vs the app surface). The higher stages **1 → 2 (custom upload → preset image)** hang on TOP later, once the backend
 * avatar shape (CYP-215) lands — this signature is the seam they extend, not replace.
 *
 * Colour resolves via [SenderPalette.forAgent]: the agent's custom [colorHex] (CYP-211) when a valid `#RRGGBB` is set,
 * else the deterministic hashed slot (null/blank/malformed → fail-safe default). Colour is **identity, never status**
 * (§1). The avatar is **decorative** for a11y — every call site renders the name as adjacent text, so it is not
 * announced here (no double-read).
 */
@Composable
fun AgentAvatar(
    id: String,
    size: Dp,
    modifier: Modifier = Modifier,
    displayName: String = id,
    role: Role? = null,
    colorHex: String? = null,
) {
    val color = SenderPalette.forAgent(id, role, colorHex)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.avatarFill)
            // CYP-209 colour ring — contrast-safe outline so the avatar reads on any background; width scales with size.
            .border(BorderStroke((size.value * 0.06f).dp.coerceAtLeast(1.dp), color.borderColor), CircleShape)
            .testTag(AvatarTags.avatar(id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(displayName.ifBlank { id }),
            color = color.onAvatar,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Convenience overload for a resolved [Agent] — pulls id/name/role/colour off the model (CYP-210 seam). */
@Composable
fun AgentAvatar(agent: Agent, size: Dp, modifier: Modifier = Modifier) =
    AgentAvatar(
        id = agent.id,
        size = size,
        modifier = modifier,
        displayName = agent.name,
        role = agent.role,
        colorHex = agent.color,
    )
