package com.tneff.cyppieagents.connector

/**
 * Stable test-tag vocabulary for the connector/capability UI (CYP-123), shared with QA (CYP-7). Grammar:
 * prefixless `connector[.<scopeId>].<element>`, each segment `[A-Za-z0-9-]+` (camelCase, no dots). Per-agent
 * tags carry the agentId scope; the selection dialog is a single surface so its tags are flat.
 */
object ConnectorTags {
    const val AREA = "connector"

    // ── compact per-agent badge ──────────────────────────────────────────────────────────────────────
    fun badge(agentId: String) = "$AREA.badge.$agentId"
    fun badgeDegraded(agentId: String) = "$AREA.badge.$agentId.degraded"
    fun badgeUnknown(agentId: String) = "$AREA.badge.$agentId.unknown"

    // ── detailed per-agent panel ─────────────────────────────────────────────────────────────────────
    fun panel(agentId: String) = "$AREA.panel.$agentId"
    fun dimension(agentId: String, dim: CapabilityDimension) = "$AREA.panel.$agentId.${dim.tagKey}"
    fun panelUnknown(agentId: String) = "$AREA.panel.$agentId.unknown"
    fun degradationHint(agentId: String) = "$AREA.panel.$agentId.degradationHint"

    // ── selection dialog (single surface) ────────────────────────────────────────────────────────────
    fun selectButton(agentId: String) = "$AREA.select.$agentId.button"
    const val SELECT_DIALOG = "$AREA.select.dialog"
    const val CURRENT = "$AREA.select.current"
    const val OPTION_STREAM_JSON = "$AREA.select.option.streamJson"
    const val OPTION_MCP = "$AREA.select.option.mcp"
    const val RISK_NOTE = "$AREA.select.riskNote"
    const val RISK_ACK = "$AREA.select.riskAck"
    const val HUMAN_ONLY = "$AREA.select.humanOnly"
    const val GATE_HINT = "$AREA.select.gateHint"
    const val CONFIRM = "$AREA.select.confirm"
    const val CANCEL = "$AREA.select.cancel"
    const val ERROR = "$AREA.select.error"
}
