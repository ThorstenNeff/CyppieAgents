package com.tneff.cyppieagents.comm

import androidx.compose.ui.graphics.Color
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.colorSlot
import com.tneff.cyppieagents.model.isPoSlot

/** Identity colours for a sender/channel: avatar fill, on-avatar text, and the name accent (CYP-14). */
data class SenderColor(
    val avatarFill: Color,
    val onAvatar: Color,
    val nameAccent: Color,
)

/**
 * Maps a `:core` [colorSlot] index → an actual colour (CYP-14 dark palette — the panel's default).
 * The PO/hub uses a reserved, distinct slot. These hues deliberately avoid the reserved CYP-12
 * status hues (running-blue / ok-green / error-red / waiting-amber) so identity never reads as status.
 */
object SenderPalette {

    private val po = SenderColor(Color(0xFF3B3F8F), Color(0xFFFFFFFF), Color(0xFFA6A9F0))

    private val senders = listOf(
        SenderColor(Color(0xFF1F7A6E), Color(0xFFFFFFFF), Color(0xFF5FD0BE)), // 0 teal
        SenderColor(Color(0xFF6E4BD0), Color(0xFFFFFFFF), Color(0xFFB9A4F2)), // 1 violet
        SenderColor(Color(0xFFB5419A), Color(0xFFFFFFFF), Color(0xFFE693D2)), // 2 magenta
        SenderColor(Color(0xFF3E63C0), Color(0xFFFFFFFF), Color(0xFF8FAAEF)), // 3 indigo
        SenderColor(Color(0xFF9A6B2F), Color(0xFFFFFFFF), Color(0xFFD9AE6E)), // 4 bronze
        SenderColor(Color(0xFFC24D6A), Color(0xFFFFFFFF), Color(0xFFF0A0B3)), // 5 pink
        SenderColor(Color(0xFF4C6A8A), Color(0xFFFFFFFF), Color(0xFF9EB8D6)), // 6 slateblue
        SenderColor(Color(0xFF6E7A2E), Color(0xFFFFFFFF), Color(0xFFBFC97E)), // 7 olive
    )

    /** Colour for a sender id (and optional [role]): the PO/hub colour, else the hashed slot colour. */
    fun forSender(id: String, role: Role? = null): SenderColor =
        if (role == Role.PO || isPoSlot(id)) po else senders[colorSlot(id, senders.size)]

    /** Channel identity colour uses the same algorithm over the channel id (CYP-14). */
    fun forChannel(channelId: String): SenderColor = senders[colorSlot(channelId, senders.size)]
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
