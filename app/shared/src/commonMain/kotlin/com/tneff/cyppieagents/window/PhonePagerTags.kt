package com.tneff.cyppieagents.window

/**
 * Centralized `Modifier.testTag` values for the phone-pager layout (CYP-50 / S10), so Compose-UI
 * tests and Maestro share one stable vocabulary. Mirrors the design test-contract in
 * `docs/design/phone-pager-tags.md` (CYP-54), Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, area `phonePager`, single-instance.
 *
 * The page selector is the **existing** `window.<id>` window id (one source of truth — same id as
 * [WindowTestTags]); page nodes host `WindowTestTags.content(id)` inside, so a window's content is
 * addressable identically in the canvas and the pager.
 *
 * Mode separation (QA contract): `phonePager.pager` exists **only** in the compact pager mode; in the
 * canvas mode only `window.host` exists. The two never coexist.
 */
object PhonePagerTags {
    /** The `HorizontalPager` container — its presence marks "compact / pager mode". */
    const val PAGER: String = "phonePager.pager"

    /** Empty state node when there are zero windows. */
    const val EMPTY: String = "phonePager.empty"

    /** Slim header bar carrying the current page's title. */
    const val HEADER: String = "phonePager.header"

    /** The current page's title text inside the header. */
    const val HEADER_TITLE: String = "phonePager.header.title"

    /** Indicator container (dots or counter). Absent when there is only a single page. */
    const val INDICATOR: String = "phonePager.indicator"

    /** Compact "N / M" position counter, used instead of dots beyond ~6 pages. */
    const val INDICATOR_POSITION: String = "phonePager.indicator.position"

    /** Previous-page affordance. */
    const val PREV: String = "phonePager.prev"

    /** Next-page affordance. */
    const val NEXT: String = "phonePager.next"

    /** One pager page, scoped by the window [id] (hosts `window.<id>.content`). */
    fun page(id: String): String = "phonePager.page.$id"

    /** Passive activity badge on the page for window [id]. */
    fun badge(id: String): String = "phonePager.page.$id.badge"

    /** A tappable indicator dot for the page of window [id] (≤ ~6 pages). */
    fun dot(id: String): String = "phonePager.indicator.dot.$id"

    /** Active-state qualifier of the dot for window [id] (set via shape/size, never colour alone). */
    fun dotActive(id: String): String = "phonePager.indicator.dot.$id.active"
}
