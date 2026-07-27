package com.tneff.cyppieagents.comm

/**
 * `testTag` contract for the comm panel — Test-Contract v0.5 §2 (`comm` area is a collection:
 * `comm.<element>[.<selectorId>]`). Single source of truth shared with the tester (CYP-7).
 */
object CommTags {
    const val CHANNEL_LIST = "comm.channelList"
    fun channel(channelId: String) = "comm.channel.$channelId"

    const val TIMELINE = "comm.timeline"
    fun message(msgId: String) = "comm.message.$msgId"

    const val COMPOSER_INPUT = "comm.composerInput"
    const val COMPOSER_SEND = "comm.composerSend"
    const val COMPOSER_READONLY = "comm.composerReadonly"

    const val CONNECTION = "comm.connection"

    /** CYP-819 (D2) — a terminal 1008 auth-revoke: the ERROR-red banner that SUPERSEDES the amber offline banner
     *  ([CONNECTION]), and the composer hard-lock hint (force-disabled independent of writability). Parity with
     *  web-ts `comm-status-revoked` / `comm-revoked-lock`; the banner copy is shared with ACL (`acl_access_revoked`). */
    const val ACCESS_REVOKED = "comm.accessRevoked"
    const val REVOKED_LOCK = "comm.revokedLock"

    const val EMPTY_CHANNELS = "comm.emptyChannels"
    const val EMPTY_TIMELINE = "comm.emptyTimeline"

    /** CYP-288 — load-error surfaces (shown INSTEAD of the empty states when a load failed) + their retry buttons. */
    const val ERROR_CHANNELS = "comm.errorChannels"
    const val ERROR_CHANNELS_RETRY = "comm.errorChannels.retry"
    const val ERROR_TIMELINE = "comm.errorTimeline"
    const val ERROR_TIMELINE_RETRY = "comm.errorTimeline.retry"

    // CYP-156: single-pane "back" affordance (compact width < PANE_COLLAPSE_WIDTH). A genuinely new
    // interactive node — there is no back today (the panel was always two-pane). Additive, follows the
    // `eventBrowse.back` precedent; PO-coordinated with QA/CYP-7 (no rename).
    const val BACK = "comm.back"
}
