package com.tneff.cyppieagents.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared responsive breakpoint (CYP-156, Klasse A): a two-pane master/detail panel collapses to
 * single-pane below this width.
 *
 * Measured against the **panel inner width** (`BoxWithConstraints { maxWidth }`), **not** the
 * screen/window — a panel can be dragged narrow inside a wide desktop window (CYP-26) or be full-width
 * in the phone pager (CYP-54); the inner-width measure is window-agnostic. `600.dp` = the Material
 * `WindowWidthSizeClass.Compact` upper bound (covers the 411dp phone + all phones).
 *
 * Rule: `maxWidth < PANE_COLLAPSE_WIDTH` ⇒ single-pane; else two-pane (the wide layout is unchanged).
 * Consumers: `comm/CommPanel`, `report/ProductLeadPanel`, `acl/AclPanel` (migrated from its local
 * `NARROW_BREAKPOINT`, value unchanged). `eventlog/EventBrowsePanel` (`TWO_PANE_MIN_WIDTH`) aligns to
 * this token in CYP-158 (its file is kept out of CYP-156).
 */
val PANE_COLLAPSE_WIDTH: Dp = 600.dp
