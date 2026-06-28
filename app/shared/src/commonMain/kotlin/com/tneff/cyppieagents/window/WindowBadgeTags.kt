package com.tneff.cyppieagents.window

/**
 * `testTag` vocabulary for per-window activity badges (CYP-55), per `docs/design/window-badges-tags.md`.
 * Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>`, area `windowBadge`, scoped by the
 * **existing** `window.<id>` window id (one source of truth — same id as [WindowTestTags]).
 *
 * Fail-closed is observable by **absence**: with no honest/allowed source there is NO `windowBadge.<id>`
 * node (never a "0"/empty container). Exactly one variant node (`.count`/`.severity`/`.attention`) is
 * present per window — the most relevant. The pager reuses the existing `phonePager.page.<id>.badge`
 * ([PhonePagerTags.badge]); CYP-55 adds no second pager badge tag.
 */
object WindowBadgeTags {
    /** Badge container for window [id] — present ⇔ there is an honest/allowed hint. */
    fun badge(id: String): String = "windowBadge.$id"

    /** Count variant — Comm unread (B1). */
    fun count(id: String): String = "windowBadge.$id.count"

    /** Severity variant — Event-Log max severity (C1), only on the gated window. */
    fun severity(id: String): String = "windowBadge.$id.severity"

    /** Attention variant — Agent ERROR (A1). */
    fun attention(id: String): String = "windowBadge.$id.attention"
}
