package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

/**
 * Like [androidx.compose.ui.platform.testTag] for Compose-UI tests / Maestro, but additionally exposes
 * the host node to **iOS UIAccessibility** so its `testTag` surfaces as `accessibilityIdentifier`
 * (CYP-67). On Android/Web/Desktop this is exactly `testTag(tag)` — no extra a11y semantics, so the
 * existing Android Maestro flows and `runComposeUiTest` keep their behaviour byte-for-byte.
 *
 * Background: Compose iOS already maps `SemanticsProperties.TestTag → accessibilityIdentifier`
 * automatically, BUT only for nodes that qualify as an iOS accessibility element. A pure-testTag
 * container (Box/Row/Column/Lazy*) without speaking semantics gets flattened by the iOS bridge and the
 * `testTag` is dropped silently. The iOS actual closes that gap by additionally setting
 * `isTraversalGroup = true`, which is the minimum semantics that promotes the node to an a11y element
 * without altering its label/role/value.
 *
 * **Use ONLY on pure-testTag containers** (Box/Row/Column/Lazy* with no own `semantics{}`,
 * `clickable`, `role`, or `contentDescription`). Sites that already carry speaking semantics
 * (Buttons, IconButtons, clickable rows with `role`/`contentDescription`) are exposed by Compose iOS
 * anyway — keep plain `testTag` there and DO NOT layer this on top (avoid double-merge or
 * TalkBack-order regress on Android).
 */
expect fun Modifier.testTagA11y(tag: String): Modifier
