package com.tneff.cyppieagents.connect

/**
 * CYP-486 live-wiring — the **hard off-default remote-hub-mode flag**. INERT discipline (like the CYP-458/459
 * server opt-in-off): when this is `false` (the default everywhere), the remote hub-connect flow is **not
 * constructed at all** ([RemoteHubConnectGate]) → byte-identical to today (0 UI affordance, 0 behaviour change).
 * The user-reachable flip (flag on + a real relay server + a deploy) stays an Auftraggeber GO — this only makes
 * the wiring *present*, not *active*.
 *
 * jvm reads an env flag (`CYP_REMOTE_HUB`); every other target is hard-`false`.
 */
expect fun remoteHubEnabled(): Boolean

/**
 * The platform's real [RemoteHubSessionFactory] — the jvm assembly of the Noise stack (`NoiseJavaClientTransport`
 * + `TofuHubTrust` + `ClientOperatorAuth`), or `null` where that stack isn't available (non-jvm). **INERT even
 * when present:** the assembly injects fail-closed seams for the still-gated pieces (relay dialer / cpJwt /
 * presented-key), so a built session connects to **nothing** (fails closed at dial), never a fake success.
 */
expect fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory?

/**
 * The production [RemoteConnectFeed]: the live [RemoteHubSessionConnectFeed] where a real (inert-seamed)
 * factory exists, else [StubRemoteConnectFeed]. Selected once; only ever reached when [remoteHubEnabled].
 */
fun defaultRemoteConnectFeed(): RemoteConnectFeed =
    defaultRemoteHubSessionFactory()?.let { RemoteHubSessionConnectFeed(it) } ?: StubRemoteConnectFeed()
