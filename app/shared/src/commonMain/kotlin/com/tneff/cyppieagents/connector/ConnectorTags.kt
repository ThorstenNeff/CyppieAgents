package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.ConnectorKind

/**
 * Test-tag vocabulary for the `connector` area (CYP-123, authoritative `docs/design/connector-capabilities-tags.md`).
 * Grammar: prefixless `connector[.<scopeId>].<element>[.<selectorId>]`, each segment `[A-Za-z0-9-]+` (camelCase,
 * no dots). `scope` ∈ { agentId (live, at the agent window) | [CAPABILITY_PREVIEW_SCOPE] (in the B opt-in dialog) };
 * `dim` = the `Capabilities` field name ([CapabilityDimension.tagKey]); `kind` selectorId = camelCase
 * `streamJson`/`mcp` (the wire `@SerialName` is `stream_json`/`mcp`). Shared with QA (CYP-7) — rename via PO.
 */
object ConnectorTags {
    const val AREA = "connector"

    // ── capability display (per agent + opt-in preview) ──────────────────────────────────────────────
    fun fidelityBadge(agentId: String) = "$AREA.$agentId.fidelityBadge"
    fun capabilityPanel(agentId: String) = "$AREA.$agentId.capabilityPanel"
    fun activeConnector(agentId: String) = "$AREA.$agentId.activeConnector"
    fun capability(scope: String, dim: CapabilityDimension) = "$AREA.$scope.capability.${dim.tagKey}"
    fun capabilityStatus(scope: String, dim: CapabilityDimension) = "$AREA.$scope.capability.${dim.tagKey}.status"

    // ── connector picker (host-anchored in agentMgmt.{add,edit}.dialog) ──────────────────────────────
    const val PICKER = "$AREA.picker"
    fun pickerOption(kind: ConnectorKind) = "$AREA.picker.${kind.selectorId}"

    // ── B opt-in dialog ──────────────────────────────────────────────────────────────────────────────
    const val OPTIN_DIALOG = "$AREA.optInDialog"
    const val OPTIN_RISK_BYPASS = "$AREA.optInDialog.riskBypass"
    const val OPTIN_RISK_ACCOUNT = "$AREA.optInDialog.riskAccount"
    const val OPTIN_RISK_FRAGILE = "$AREA.optInDialog.riskFragile"
    const val OPTIN_PREVIEW = "$AREA.optInDialog.capabilityPreview"
    const val OPTIN_ACK = "$AREA.optInDialog.ack"
    const val OPTIN_HUMAN_ONLY = "$AREA.optInDialog.humanOnly"
    const val OPTIN_CONFIRM = "$AREA.optInDialog.confirm"
    const val OPTIN_CANCEL = "$AREA.optInDialog.cancel"
    const val OPTIN_ERROR = "$AREA.optInDialog.error"
}

/** The B-opt-in capability-preview scope (a fixed scope id, distinct from any agentId). */
const val CAPABILITY_PREVIEW_SCOPE = "preview"

/** camelCase tag selectorId for a connector kind (wire `@SerialName` is `stream_json`/`mcp`). */
val ConnectorKind.selectorId: String
    get() = when (this) {
        ConnectorKind.STREAM_JSON -> "streamJson"
        ConnectorKind.MCP -> "mcp"
    }
