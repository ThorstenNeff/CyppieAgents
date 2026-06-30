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
    const val EMPTY_CHANNELS = "comm.emptyChannels"
    const val EMPTY_TIMELINE = "comm.emptyTimeline"
}
