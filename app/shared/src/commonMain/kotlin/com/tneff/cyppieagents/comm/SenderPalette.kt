package com.tneff.cyppieagents.comm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.colorSlot
import com.tneff.cyppieagents.model.deriveScheme
import com.tneff.cyppieagents.model.isPoSlot
import com.tneff.cyppieagents.model.parseHexColor

/**
 * Identity colours for a sender/channel: avatar fill, on-avatar text, the name accent (CYP-14), and — CYP-211 —
 * a contrast-safe [borderColor] (≥3:1 vs the app surface) derived via the shared `:core` [deriveScheme].
 */
data class SenderColor(
    val avatarFill: Color,
    val onAvatar: Color,
    val nameAccent: Color,
    val borderColor: Color,
)

/**
 * Maps a `:core` [colorSlot] index → an actual colour (CYP-14 dark palette — the panel's default).
 * The PO/hub uses a reserved, distinct slot. These hues deliberately avoid the reserved CYP-12
 * status hues (running-blue / ok-green / error-red / waiting-amber) so identity never reads as status.
 */
object SenderPalette {

    // CYP-211: [borderColor] is DERIVED from the fill via the shared `:core` deriveScheme (one formula, no drift);
    // fill/on/accent are the CYP-14 values unchanged.
    private fun slot(fill: Long, on: Long, accent: Long): SenderColor {
        val fillColor = Color(fill)
        return SenderColor(
            avatarFill = fillColor,
            onAvatar = Color(on),
            nameAccent = Color(accent),
            borderColor = Color(deriveScheme(fillColor.toArgb()).border),
        )
    }

    private val po = slot(0xFF3B3F8F, 0xFFFFFFFF, 0xFFA6A9F0)

    private val senders = listOf(
        slot(0xFF1F7A6E, 0xFFFFFFFF, 0xFF5FD0BE), // 0 teal
        slot(0xFF6E4BD0, 0xFFFFFFFF, 0xFFB9A4F2), // 1 violet
        slot(0xFFB5419A, 0xFFFFFFFF, 0xFFE693D2), // 2 magenta
        slot(0xFF3E63C0, 0xFFFFFFFF, 0xFF8FAAEF), // 3 indigo
        slot(0xFF9A6B2F, 0xFFFFFFFF, 0xFFD9AE6E), // 4 bronze
        slot(0xFFC24D6A, 0xFFFFFFFF, 0xFFF0A0B3), // 5 pink
        slot(0xFF4C6A8A, 0xFFFFFFFF, 0xFF9EB8D6), // 6 slateblue
        slot(0xFF6E7A2E, 0xFFFFFFFF, 0xFFBFC97E), // 7 olive
    )

    /** The 8 palette swatches (CYP-211 swatch picker) — index 0..7, PO's reserved slot excluded. */
    val swatches: List<SenderColor> get() = senders

    /** Colour for a sender id (and optional [role]): the PO/hub colour, else the hashed slot colour. */
    fun forSender(id: String, role: Role? = null): SenderColor =
        if (role == Role.PO || isPoSlot(id)) po else senders[colorSlot(id, senders.size)]

    /** Channel identity colour uses the same algorithm over the channel id (CYP-14). */
    fun forChannel(channelId: String): SenderColor = senders[colorSlot(channelId, senders.size)]

    /**
     * CYP-211: a full [SenderColor] from an arbitrary base ARGB (a custom hex / palette pick) via the shared
     * `:core` derivation — onColor + border are contrast-safe. `nameAccent` falls back to the derived border
     * (a contrasting variant of the base) since a custom colour has no bespoke CYP-14 accent.
     */
    fun fromBase(baseArgb: Int): SenderColor {
        val s = deriveScheme(baseArgb)
        return SenderColor(Color(s.background), Color(s.onColor), Color(s.border), Color(s.border))
    }

    /**
     * CYP-211 — the resolved identity colour for an agent: its custom [colorHex] (via [fromBase]) when a valid
     * `#RRGGBB` is set, else the deterministic [forSender] slot default (null/blank/malformed → fail-safe default,
     * backward-compatible with agents that never set a colour).
     */
    fun forAgent(id: String, role: Role?, colorHex: String?): SenderColor {
        val base = colorHex?.let { parseHexColor(it) }
        return if (base != null) fromBase(base) else forSender(id, role)
    }
}

/** Initials from an agent display name (CYP-14 avatar, no image asset in MVP). */
fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
