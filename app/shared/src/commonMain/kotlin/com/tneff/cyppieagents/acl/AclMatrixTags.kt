package com.tneff.cyppieagents.acl

/**
 * `testTag` contract for the ACL-matrix panel (CYP-48) — schema from `docs/design/acl-matrix-tags.md`
 * (CYP-19), Test-Contract v0.5 §2: `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
 * prefixless, segment values `[A-Za-z0-9-]+` (no dots — they collide with the separator and Maestro's
 * regex selector). Area `aclMatrix` (single-instance). **Shared API with QA (CYP-7) — do not rename
 * silently; coordinate via the PO.**
 */
object AclMatrixTags {
    const val AREA = "aclMatrix"

    const val GRID = "aclMatrix.grid"
    const val EMPTY = "aclMatrix.empty"
    const val PARTIAL_VIEW = "aclMatrix.partialView"
    const val CONNECTION = "aclMatrix.connection"
    const val ACCESS_REVOKED = "aclMatrix.accessRevoked"

    fun colHeader(agentId: String) = "aclMatrix.colHeader.$agentId"
    fun rowHeader(channelId: String) = "aclMatrix.rowHeader.$channelId"

    fun cell(channelId: String, agentId: String) = "aclMatrix.cell.$channelId.$agentId"
    fun read(channelId: String, agentId: String) = "${cell(channelId, agentId)}.read"
    fun write(channelId: String, agentId: String) = "${cell(channelId, agentId)}.write"
    fun readonly(channelId: String, agentId: String) = "${cell(channelId, agentId)}.readonly"

    /** Disclosure qualifier on a cell — exactly one of [CellQualifier]'s wire values. */
    fun cellQualifier(channelId: String, agentId: String, qualifier: CellQualifier) =
        "${cell(channelId, agentId)}.${qualifier.tag}"

    // CYP-189 — Human-vs-Agent subject bands + the human-subject marker. The `<agentId>` slot carries a
    // human's `identityId` unchanged (Kratos-UUID: hyphens, no dots → a valid selector segment). Humans
    // render ONLY for the operator (Invariante E): [HUMANS_GROUP] and every human column/cell are
    // STRUCTURALLY ABSENT in the non-operator/partialView tree — QA asserts the no-roster-leak by absence.
    const val AGENTS_GROUP = "aclMatrix.agentsGroup"
    const val HUMANS_GROUP = "aclMatrix.humansGroup"
    /** Qualifier on a human subject's column header → "this subject is a Human, not an Agent". */
    fun humanMarker(identityId: String) = "${colHeader(identityId)}.human"

    // PO guardrail + server protection.
    const val LOCKOUT_DIALOG = "aclMatrix.lockoutDialog"
    const val LOCKOUT_DIALOG_CONFIRM = "aclMatrix.lockoutDialog.confirm"
    const val LOCKOUT_DIALOG_CANCEL = "aclMatrix.lockoutDialog.cancel"
    const val SELF_BLIND_WARNING = "aclMatrix.selfBlindWarning"
    fun protected(channelId: String, agentId: String) = cellQualifier(channelId, agentId, CellQualifier.PROTECTED)

    // Preset "restore hub-and-spoke" (non-atomic, N PUTs).
    const val PRESET_RESTORE = "aclMatrix.presetRestore"
    const val PRESET_PREVIEW = "aclMatrix.presetPreview"
    const val PRESET_PREVIEW_CONFIRM = "aclMatrix.presetPreview.confirm"
    const val PRESET_PREVIEW_CANCEL = "aclMatrix.presetPreview.cancel"
    const val PRESET_PROGRESS = "aclMatrix.presetProgress"
    const val PRESET_PARTIAL = "aclMatrix.presetPartial"
}

/** The cell disclosure qualifiers (acl-matrix-tags.md §2/§0 vocabulary). */
enum class CellQualifier(val tag: String) {
    PENDING("pending"),
    ENFORCED("enforced"),
    NON_MEMBER("nonMember"),
    PO_CRITICAL("poCritical"),
    CONFLICT("conflict"),
    PROTECTED("protected"),
}
