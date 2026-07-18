package com.tneff.cyppieagents.firstrun

/**
 * CYP-629 live-wiring — the first-run gate's opt-in flag. **Live-by-default** (PO ruling 2026-07-18): the gate is
 * self-disabling — a *configured* hub is [FirstRunGateMode.TRANSPARENT] and passes straight through (zero visible
 * change on Staging / an already-set-up hub), so defaulting it ON is safe and matches the go-live intent. Only a
 * fresh, **unconfigured** hub (the BYOA `.deb` first-install case) shows the wizard — exactly the intent.
 *
 * jvm honors an env **kill-switch** (`CYP_FIRST_RUN=false`) as the ripcord. iOS is a stub target (no real workspace)
 * → off. The gate itself is additionally operator-gated at the mount (`firstRunGateEnabled() && isOperator`): a
 * non-operator can't configure the hub, so they pass through and get the honest degraded workspace (§6.3c) instead.
 */
expect fun firstRunGateEnabled(): Boolean
