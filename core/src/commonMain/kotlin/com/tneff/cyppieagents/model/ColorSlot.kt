package com.tneff.cyppieagents.model

/**
 * Sender/channel identity colour-slot assignment (CYP-14). Pure and shared so the server and every
 * client colour the same id identically — `:core` returns only the **Int slot index** (compose-free);
 * mapping index → actual colour lives in `:app:shared` (the dark/light palette).
 *
 * NOTE: implemented here as part of CYP-21 because CYP-14 is design-only and the Comm-Panel needs it;
 * placement in `:core` is the PO-confirmed home. Flagged for ticket attribution.
 */

/** Size of the sender identity palette (8 distinct hues, CYP-14 `sender_palette`). */
const val SENDER_PALETTE_SIZE: Int = 8

/**
 * Explicit slots for known MVP agents so demo colours are predictable (CYP-14 `knownSlots`:
 * frontend → sender.0, backend → sender.1). The PO uses a **reserved hub slot** handled by the
 * caller via a role/id check — deliberately not part of the sender palette here.
 */
private val KNOWN_SENDER_SLOTS: Map<String, Int> = mapOf(
    "frontend" to 0,
    "backend" to 1,
)

/** True when [id] should use the reserved PO/hub colour rather than a [colorSlot] index. */
fun isPoSlot(id: String): Boolean = id == PO_AGENT_ID

/**
 * Deterministic palette slot for [id] in `0 until [paletteSize]`: a fixed slot for known ids,
 * otherwise an FNV-1a (32-bit) hash. No randomness, no runtime seed — stable across sessions and
 * clients. Collisions are acceptable because avatar+name carry the real identity (CYP-14 §2).
 */
fun colorSlot(id: String, paletteSize: Int = SENDER_PALETTE_SIZE): Int {
    require(paletteSize > 0) { "paletteSize must be positive" }
    KNOWN_SENDER_SLOTS[id]?.let { if (it < paletteSize) return it }
    var hash = 2166136261u
    for (byte in id.encodeToByteArray()) {
        hash = hash xor byte.toUInt()
        hash *= 16777619u
    }
    return (hash % paletteSize.toUInt()).toInt()
}
