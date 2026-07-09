package com.tneff.cyppieagents.compact

/**
 * CYP-326 — testTag contract for the compact-orchestration window (UIUX spec §1.6). Prefixless, camelCase-
 * after-dot (convention `compact.<element>`, consistent with `settings.*`/`acl.*`/`eventTail.*`). Shared with
 * the tester — an API between Dev and QA, not renamed silently. The window frame tag `window.compact` derives
 * automatically from [com.tneff.cyppieagents.window.WindowTestTags].
 */
object CompactTags {
    const val PANEL = "compact.panel"

    /** The operator's "compact allowed" checkbox (present only for an operator). */
    const val ALLOW_TOGGLE = "compact.allowToggle"

    /** The read-only "compact allowed" status chip shown to a non-operator (honesty: no fake switch). */
    const val ALLOW_CHIP = "compact.allowChip"

    /** The mandatory INFO disclosure hint (always visible — the honest consequence, §3-1). */
    const val ALLOW_HINT = "compact.allowHint"

    /** The GATED "only the operator can change this" hint (non-operator only). */
    const val GATE_HINT = "compact.gateHint"

    /** The read-only threshold fact row. */
    const val THRESHOLD = "compact.threshold"

    /** The server-mirror status row (liveRegion). Absent when the server state is unknown (§3-3). */
    const val STATUS = "compact.status"

    /** The last-run X/N row. Absent before the first run; WARN-toned on a timeout. */
    const val LAST_RUN = "compact.lastRun"
}
