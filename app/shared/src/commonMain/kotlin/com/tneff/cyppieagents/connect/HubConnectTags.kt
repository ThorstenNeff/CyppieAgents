package com.tneff.cyppieagents.connect

/**
 * CYP-416 (Epic CYP-395 S-M) — test tags for the hubConnect §B3 **mode-chooser**, per the frozen UIUX spec
 * (`docs/design/hub-connection-tags.md @8265e4fc`). Only the three mode nodes (+ the Remote "kommt bald" hint)
 * land now; the §6 connect-state and hub-list tags arrive with S-L.
 */
object HubConnectTags {
    /** Local option — active, default focus. */
    const val MODE_LOCAL = "hubConnect.mode.local"

    /** Remote option — non-interactive / disabled ("kommt bald", H2); never preselected. */
    const val MODE_REMOTE = "hubConnect.mode.remote"

    /** The honest "kommt bald" GATED hint beneath the disabled Remote option (`hubconnect_mode_remote_soon`). */
    const val MODE_REMOTE_SOON = "hubConnect.mode.remote.soon"

    /** Primary action "Verbinden" → local connect (§6 flow lands in S-L). */
    const val MODE_CONNECT = "hubConnect.mode.connect"
}
