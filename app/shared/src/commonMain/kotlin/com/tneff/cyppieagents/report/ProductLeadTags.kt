package com.tneff.cyppieagents.report

/**
 * `testTag` contract for the Product-Lead report panel (CYP-90), exactly per
 * `docs/design/product-lead-tags.md` (Epic CYP-77). Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, segment values `[A-Za-z0-9-]+`
 * (camelCase, no dots). Area `productLead`, single-instance. **Shared API with QA (CYP-7) — do not
 * rename silently; coordinate via the PO.**
 *
 * The window mounts under `window.productLead.content`; the defect rail reuses the CYP-34 `severity_rail`
 * token + `event_severity_*` labels — no second severity schema.
 */
object ProductLeadTags {
    const val AREA = "productLead"

    const val PANEL = "productLead.panel"
    const val TRIGGER = "productLead.trigger"
    const val TRIGGER_USAGE = "productLead.trigger.usage"
    const val TRIGGER_STATUS = "productLead.trigger.status"
    const val TRIGGER_DEFECTS = "productLead.trigger.defects"
    const val GENERATING = "productLead.generating"
    const val LIST = "productLead.list"
    const val DETAIL = "productLead.detail"
    const val DETAIL_AS_OF = "productLead.detail.asOf"
    const val DETAIL_PROVENANCE = "productLead.detail.provenance"
    const val DETAIL_ADVISORY = "productLead.detail.advisory"
    const val EMPTY = "productLead.empty"
    const val GATE_HINT = "productLead.gateHint"
    const val ERROR = "productLead.error"

    // CYP-156: single-pane "back" affordance (compact width < PANE_COLLAPSE_WIDTH). A genuinely new
    // interactive node — there is no back today (the panel was always two-pane). Additive, follows the
    // `eventBrowse.back` precedent; PO-coordinated with QA/CYP-7 (no rename).
    const val BACK = "productLead.back"

    fun snapshot(id: String) = "productLead.snapshot.$id"
    fun snapshotTs(id: String) = "productLead.snapshot.$id.ts"
    fun section(key: String) = "productLead.detail.section.$key"
    fun defect(index: Int) = "productLead.detail.defect.$index"
    fun defectSeverity(index: Int, severity: String) = "productLead.detail.defect.$index.$severity"
}
