package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics

// iOS surfaces Compose `SemanticsProperties.TestTag` as `UIAccessibility.accessibilityIdentifier`
// automatically (Compose-Multiplatform 1.11.x / compose-ui 1.9.x — Accessibility.uikit.kt L196-197),
// but ONLY for nodes that qualify as an iOS accessibility element. Pure-testTag containers (without
// speaking semantics) get flattened during the iOS bridge build (L1132 / L1168-1216) and the testTag
// is dropped silently. Setting `isTraversalGroup = true` is the minimum-impact semantics that promotes
// the container to an a11y element so its testTag becomes the addressable identifier — no role,
// label, or value change → no shift to the screen-reader's spoken content.
//
// We use `mergeDescendants = false` deliberately: merging would collapse all descendant testTags into
// this node and break per-descendant addressing.
actual fun Modifier.testTagA11y(tag: String): Modifier =
    this.testTag(tag).semantics(mergeDescendants = false) { isTraversalGroup = true }
