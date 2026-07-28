package com.tneff.cyppieagents.comm

/**
 * CYP-883 (OS-C) — `testTag` contract for the operator channel-management panel (parity with web-ts
 * `channel-mgmt.*`). Single source of truth shared with the tester.
 */
object ChannelMgmtTags {
    const val ROOT = "channelMgmt.root"

    /** The HONEST, server-authoritative error (409 protected-HUB / 403 operator-only / generic) — never hidden. */
    const val ERROR = "channelMgmt.error"

    const val CREATE_ID = "channelMgmt.create.id"
    const val CREATE_NAME = "channelMgmt.create.name"
    const val CREATE_SUBMIT = "channelMgmt.create.submit"

    /** A creatable-kind selector chip (DIRECT/GROUP). */
    fun createKind(kind: String) = "channelMgmt.create.kind.$kind"

    /** A candidate-member toggle for the create form. */
    fun createMember(agentId: String) = "channelMgmt.create.member.$agentId"

    fun row(channelId: String) = "channelMgmt.row.$channelId"
    fun rename(channelId: String) = "channelMgmt.rename.$channelId"
    fun renameSubmit(channelId: String) = "channelMgmt.rename.submit.$channelId"

    /** Archive — disabled for a protected HUB (client hint; server 409 is the authoritative guard). */
    fun archive(channelId: String) = "channelMgmt.archive.$channelId"
}
