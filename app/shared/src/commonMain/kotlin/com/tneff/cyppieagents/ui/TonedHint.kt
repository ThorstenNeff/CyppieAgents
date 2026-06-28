package com.tneff.cyppieagents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The four hint **tones** shared by the Settings (CYP-85) and Agent-Management (CYP-86/87/88) panels
 * (CYP-99). One vocabulary so "saved ≠ active" reads as **Attention** everywhere, not as a neutral grey:
 *
 * - [EFFECT_DEFERRED] — "saved, not yet active" → an amber container (Attention), not plain text.
 * - [GATED] — operator-gate explanation → neutral.
 * - [INFO] — a non-blocking informational note → secondary.
 * - [ERROR] — a real error / data-loss warning → error.
 *
 * **Colour is never the sole carrier (WCAG 1.4.1):** each tone also has a distinct leading text glyph,
 * and the meaning lives in the message text — so the tones are distinguishable without colour.
 */
enum class HintTone { EFFECT_DEFERRED, GATED, INFO, ERROR }

/**
 * A single toned hint line: a leading tone glyph + the [text]. [EFFECT_DEFERRED] additionally renders in a
 * filled amber container so it reads as Attention. [tag] is the node's testTag (the design's hint id); the
 * glyph is decorative (its meaning is the text), so it is hidden from the a11y tree.
 */
@Composable
fun TonedHint(text: String, tone: HintTone, tag: String, modifier: Modifier = Modifier) {
    val content = hintContentColor(tone)
    val banner = tone == HintTone.EFFECT_DEFERRED
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(
                if (banner) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Tone glyph — the non-colour signal (WCAG 1.4.1): distinct per tone, distinguishable without
        // colour. Kept in the tree (a meaningful symbol; the message text still carries the full meaning).
        Text(
            text = hintGlyph(tone),
            color = content,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = 0.dp),
        )
    }
}

/** The non-colour tone signal (WCAG 1.4.1). Plain text glyphs (no emoji — CYP-54), reliable on Desktop-JVM. */
fun hintGlyph(tone: HintTone): String = when (tone) {
    HintTone.EFFECT_DEFERRED -> "!"
    HintTone.ERROR -> "✕"
    HintTone.INFO -> "i"
    HintTone.GATED -> "·"
}

@Composable
private fun hintContentColor(tone: HintTone): Color = when (tone) {
    HintTone.EFFECT_DEFERRED -> MaterialTheme.colorScheme.onTertiaryContainer
    HintTone.ERROR -> MaterialTheme.colorScheme.error
    HintTone.INFO -> MaterialTheme.colorScheme.secondary
    HintTone.GATED -> MaterialTheme.colorScheme.onSurfaceVariant
}
