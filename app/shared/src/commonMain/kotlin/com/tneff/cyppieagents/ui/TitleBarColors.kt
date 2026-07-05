package com.tneff.cyppieagents.ui

import androidx.compose.ui.graphics.Color

/**
 * CYP-211 — the derived titlebar colours for a window (agent identity theming). [background]/[content]/[border]
 * come from the shared `:core` `deriveScheme` (WCAG-safe: content ≥ 4.5:1, border ≥ 3:1). Lives in the neutral
 * `ui` package (CYP-216) so BOTH `window` (the titlebar chrome) and the shared `AgentAvatarView` (the §5.1 inverted
 * titlebar disc) can use it without a `window→comm`/`ui→window` coupling — the shell maps its resolved identity
 * colour into this small chrome type.
 */
data class TitleBarColors(
    val background: Color,
    val content: Color,
    val border: Color,
)
