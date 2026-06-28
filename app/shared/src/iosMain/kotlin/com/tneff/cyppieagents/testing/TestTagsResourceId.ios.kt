package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

// iOS has no Android-style global `testTagsAsResourceId` flag that a single root modifier could flip
// to propagate testTags as accessibility identifiers to descendants (Compose-Multiplatform 1.11.x /
// compose-ui 1.9.x). The iOS bridge already maps `SemanticsProperties.TestTag → accessibilityIdentifier`
// automatically, but only for nodes that qualify as a11y elements; pure-testTag containers get
// flattened. The fix is **per-site**: use `Modifier.testTagA11y(tag)` (TestTagA11y.kt, CYP-67) on the
// Maestro-relevant containers. This root entry stays a no-op on iOS so the cross-target call sites
// stay identical (App root, AclPanel dialog re-root, …).
actual fun Modifier.enableTestTagsAsResourceId(): Modifier = this
