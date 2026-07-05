package com.tneff.cyppieagents.avatar

import java.io.File
import java.security.MessageDigest

/**
 * CYP-215 — resolves a self-hosted DiceBear [com.tneff.cyppieagents.model.AgentAvatar.Preset] `(style,
 * seed)` to PNG bytes, **egress-free** (never a call to api.dicebear.com). The bytes come from a bundled /
 * pre-generated set of PNGs on disk (under `<presetsRoot>/<style>/`); [seed] deterministically picks one variant.
 *
 * **The security-relevant part is the allow-list, which is enforced NOW:** [ALLOWED_STYLES] is the fixed
 * set UIUX/CYP-214 license-cleared (bottts/avataaars = attribution-free; adventurer/big-smile/fun-emoji =
 * CC BY 4.0, attribution carried in-app). The write path REJECTS an unknown style fail-closed
 * ([requireAllowedStyle]); the client never dictates the set.
 *
 * **Asset bundling is a deploy step (documented, not code):** populate `<presetsRoot>/<style>/` offline via
 * the DiceBear CLI — `dicebear <style> <out> --seed "<seed>" --format png` (or `--count N`). Until a
 * style's set is present [resolve] returns null → the serve endpoint 404s → the client falls back to its
 * deterministic default (the CYP-210 colour slot). So presets degrade gracefully; nothing hard-fails.
 */
class AvatarPresetResolver(private val presetsRoot: File?) {

    /** True iff [style] is one of the license-cleared, self-hosted styles. */
    fun isAllowed(style: String): Boolean = style in ALLOWED_STYLES

    /** Fail-closed guard for the write path: throws if the style is not license-cleared/self-hosted. */
    fun requireAllowedStyle(style: String) {
        if (!isAllowed(style)) {
            throw IllegalArgumentException("avatar preset style '$style' is not allowed (self-hosted set: ${ALLOWED_STYLES.joinToString()})")
        }
    }

    /**
     * The PNG bytes for `(style, seed)`, or null if the style is disallowed / the set isn't bundled yet /
     * persistence is disabled. Deterministic: a given `(style, seed)` always maps to the same bundled file.
     */
    fun resolve(style: String, seed: String): ByteArray? {
        if (!isAllowed(style)) return null
        val root = presetsRoot ?: return null
        val dir = File(root, style)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val idx = (seedHash(seed) % files.size).toInt()
        return files[idx].readBytes()
    }

    /** Stable non-negative hash of the seed (deterministic across runs — never `hashCode()`). */
    private fun seedHash(seed: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(seed.encodeToByteArray())
        var acc = 0L
        for (i in 0 until 8) acc = (acc shl 8) or (d[i].toLong() and 0xFF)
        return acc and Long.MAX_VALUE // drop the sign bit → non-negative
    }

    companion object {
        /**
         * The fixed, license-cleared self-hosted style set (UIUX/CYP-214, relayed by the PO 2026-07-05).
         * bottts/avataaars = attribution-free (Pablo Stanley); adventurer/big-smile/fun-emoji = CC BY 4.0
         * with in-app attribution (UIUX/Dev) → no backend blocker. Unknown style → reject fail-closed.
         */
        val ALLOWED_STYLES: Set<String> = setOf("bottts", "avataaars", "adventurer", "big-smile", "fun-emoji")
    }
}
