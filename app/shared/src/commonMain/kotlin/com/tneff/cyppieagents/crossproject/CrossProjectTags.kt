package com.tneff.cyppieagents.crossproject

/**
 * `testTag` contract for cross-project channel authorization (CYP-93), exactly per
 * `docs/design/cross-project-tags.md` (Epic CYP-79). Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>`, segment values `[A-Za-z0-9-]+` (camelCase, no dots in a value).
 * New area `crossProject`. Host-anchored into the existing Comm/ACL channel context (`comm.channel.<id>` /
 * `aclMatrix.rowHeader.<channelId>`) — these are cross-project's own tags, not a reuse of those.
 */
object CrossProjectTags {

    fun badge(channelId: String) = "crossProject.badge.$channelId"
    /** Qualifier: the channel spans projects but is NOT (yet) authorized — fail-closed. */
    fun badgeUnauthorized(channelId: String) = "crossProject.badge.$channelId.unauthorized"

    const val STATUS = "crossProject.status"

    /** A foreign-project member's home-project marker in the channel members / ACL view. */
    fun member(agentId: String) = "crossProject.member.$agentId"

    const val AUTHORIZE = "crossProject.authorize"
    const val REVOKE = "crossProject.revoke"
    const val GATE_HINT = "crossProject.gateHint"

    const val DIALOG = "crossProject.dialog"
    const val DIALOG_SCOPE = "crossProject.dialog.scope"
    const val DIALOG_OWNER_CONSENT = "crossProject.dialog.ownerConsent"
    const val DIALOG_HUMAN_ONLY = "crossProject.dialog.humanOnlyNote"
    const val DIALOG_SINGLE_OWNER = "crossProject.dialog.singleOwnerNote"
    const val DIALOG_CONFIRM = "crossProject.dialog.confirm"
    const val DIALOG_CANCEL = "crossProject.dialog.cancel"
    const val DIALOG_ERROR = "crossProject.dialog.error"
}
