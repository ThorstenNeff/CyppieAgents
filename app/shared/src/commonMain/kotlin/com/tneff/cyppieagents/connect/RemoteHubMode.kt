package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import kotlinx.coroutines.CoroutineScope

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
 * CYP-513 — the LIVE [RemoteConnectComponentsFactory] (activation): the jvm impl composes the real remote-connect
 * stack from env config (CP base URL + relay URL), or `null` where remote isn't available / not configured
 * (INERT). App.kt passes it flag-gated ([remoteHubEnabled]); the user-reachable flip (config + a relay server +
 * a deploy) stays an Auftraggeber GO. [operatorToken] is the app session token (`Bearer`, same-origin CP auth).
 */
expect fun defaultRemoteComponentsFactory(operatorToken: () -> String?): RemoteConnectComponentsFactory?

/**
 * S-J — the [ControlPlaneClient] the hub-connect flow consumes (hub list + register). jvm returns the live
 * [HttpControlPlaneClient] **only when `CYPPIE_CP_BASE_URL` is set** (env-gated); absent, and on every non-desktop
 * target, it returns [StubControlPlaneClient] → INERT, byte-identical to the CYP-419 stub default. Only ever
 * reached when [remoteHubEnabled] (the gate mounts the flow). The live swap + a deploy stay an Auftraggeber GO;
 * building this only makes the wiring *present*, off unless the env is configured. [operatorToken] is the app
 * session token (`Bearer`, same-origin CP auth — same source as [defaultRemoteComponentsFactory]).
 */
expect fun defaultControlPlaneClient(operatorToken: () -> String?): ControlPlaneClient

/**
 * The production [RemoteConnectFeed]: the live [RemoteHubSessionConnectFeed] where a real (inert-seamed)
 * factory exists, else [StubRemoteConnectFeed]. Selected once; only ever reached when [remoteHubEnabled].
 */
fun defaultRemoteConnectFeed(): RemoteConnectFeed =
    defaultRemoteHubSessionFactory()?.let { RemoteHubSessionConnectFeed(it) } ?: StubRemoteConnectFeed()

/**
 * CYP-504 — the **seam-injectable** session builder. [defaultRemoteHubSessionFactory] (jvm) delegates here with
 * the GATED prod seams (throwing [dialer] / real per-hub TOFU [trust] over an empty presented-key registry /
 * [authenticator] whose `cpJwt` is `null` ⇒ fail-closed); tests inject the passing/gated forms to prove — per
 * seam — that CONNECTED is reachable only when **all** of {dial, trust, auth} pass, and that dial fails closed
 * **before** [HubTrust.resolve] is even reached. This is the defence-in-depth that lifts the CYP-486 "fails
 * closed at dial" tooth from result-blind to load-bearing: even if two seams are opened too early at activation,
 * the third still blocks. The builder itself adds no policy — it only wires the injected seams into the
 * [RemoteHubSession] state machine, so prod behaviour is byte-identical to the inline assembly it replaces.
 */
internal fun buildRemoteHubSession(
    hubId: String,
    transport: ClientNoiseTransport,
    dialer: RelayDialer,
    trust: HubTrust,
    authenticator: OperatorAuthenticator,
    scope: CoroutineScope,
): RemoteHubSession = RemoteHubSession(
    hubId = hubId,
    transport = transport,
    dialer = dialer,
    trust = trust,
    authenticator = authenticator,
    scope = scope,
)
