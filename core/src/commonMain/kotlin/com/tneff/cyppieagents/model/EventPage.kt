package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * A page of [Event]s in ascending `seq` order — the `/api/events` REST wire shape (CYP-39, owner-
 * confirmed with Dev). Compiled into both `:server` (the `EventSink` query result) and `:app:shared`
 * (the Browse UI) via `:core`, so the page shape can't drift.
 *
 * Paging is **stable over `seq`** (PRD §6): [nextAfterSeq] is the cursor for the next page (the last
 * event's `seq`), or null at the end. [hasMore] mirrors it explicitly for the UI.
 */
@Serializable
data class EventPage(
    val events: List<Event>,
    val nextAfterSeq: Long? = null,
    val hasMore: Boolean = nextAfterSeq != null,
)
