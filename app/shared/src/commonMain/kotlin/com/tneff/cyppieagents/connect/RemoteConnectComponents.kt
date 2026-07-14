package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.CachingUserVerification
import com.tneff.cyppieagents.net.hub.operator.vault.DecryptedKeyHold
import com.tneff.cyppieagents.net.hub.operator.vault.OperatorEnrollController
import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import kotlinx.coroutines.CoroutineScope

/**
 * CYP-513 — the per-connect remote pieces produced together so they SHARE the ①② instances: the live
 * [RemoteHubSession] (its `TofuHubTrust` and this [oobConfirm] coordinator are built from ONE `of(hub)` +
 * ONE `PendingOobConfirmations` — see [buildSharedHubTrustComponents]). The ViewModel drives the session and
 * surfaces the coordinator's OOB state, so what the operator confirms is what gets pinned (display == pinned),
 * end-to-end.
 */
class RemoteConnectComponents(
    val session: RemoteHubSession,
    val oobConfirm: OobConfirmCoordinator,
    /** CYP-525 §2 — the live first-enroll confirmer (the SAME instance injected into the session's
     *  `ClientOperatorAuth`), so the reveal the VM surfaces IS what gates the `SavedAck` the session sends. */
    val enrollConfirm: EnrollConfirmCoordinator = LiveEnrollConfirmCoordinator(),
    /**
     * CYP-537 (M2 Option A, WS2) — the N-tunnel pool: the transport's `TunnelSource` on CONNECTED, so the mode-blind
     * workspace's concurrent WS each ride a **distinct** authenticated tunnel (F-M2-1 fix). `null` ⇒ the transport
     * falls back to the single-flight session tunnel (the 1-connection thru-cut). Closed with the session on a Q5
     * switch/leave (nothing carried across). Shares the session's trust/device seams (pool tunnels ride the pin).
     */
    val tunnelPool: PooledTunnelSource? = null,
    /**
     * CYP-542 / B1 (CYP-547) — the **shared** [CachingUserVerification] instance the session-auth AND the pool-auth
     * both run through (store.userVerification IS it; the pool's authenticator IS the same operatorAuth). Exposed so
     * Tester can drive `operatorUvCache.verify(OPERATOR_AUTH)` N× **directly** on the real shared instance (the
     * behavioral 1-UV-for-N `prompts==1` guard) — `ClientOperatorAuth.authenticate` short-circuits at the cpJwt
     * network boundary before the UV, so a headless `authenticate` accessor can't reach it. `null` off the live factory.
     */
    val operatorUvCache: CachingUserVerification? = null,
    /**
     * CYP-542 / B1 — the auth-time passphrase prompt bridge (the CYP-460 dialog is driven by its `state`; the VM
     * surfaces `Prompting`/`submit`/`cancel`). A **VM-lifetime singleton** (the live factory hoists ONE instance
     * across every `create()` so a post-enroll [preArm][PassphrasePromptCoordinator.preArm] survives the
     * enroll→reconnect cycle — the AC-2 no-second-prompt path). `null` ⇒ INERT (no real UV prompt wired).
     */
    val passphrasePrompt: PassphrasePromptCoordinator? = null,
    /**
     * CYP-542 / B1 — the set-passphrase controller the VM drives when the hub reports [DeviceNotEnrolled]
     * [com.tneff.cyppieagents.net.hub.remote.RemoteFailure.DeviceNotEnrolled] (AC-1: route to the enroll step, not a
     * retry-reloop). Enroll success → [PassphrasePromptCoordinator.preArm] + reconnect (AC-2). `null` ⇒ INERT.
     */
    val enroll: OperatorEnrollController? = null,
    /**
     * CYP-542 / B1 (Assist BLOCK-1) — the decrypted device-key hold, exposed so the VM **zeroizes it on session
     * teardown** ([HubConnectViewModel.closeActiveComponents] → `keyHold.clear()`, next to `clearPreArm()`). The hold's
     * lazy `clear()` only fires on `put()`/`get()`-at-expiry, so a switch/leave/cancel WITHIN the ≤120s reuse window
     * would otherwise drop the object graph with the crown-jewel Ed25519 key still un-zeroized + GC-reachable (H-1
     * violation on the most common path). The proactive window-expiry timer (the hold's own `scope`) covers the idle
     * path; this field covers teardown. `null` ⇒ INERT (no vault-backed hold; nothing to zeroize).
     */
    val keyHold: DecryptedKeyHold? = null,
)

/**
 * Builds the [RemoteConnectComponents] for a hub within a connecting scope. The jvm activation impl
 * (`liveRemoteConnectComponentsFactory`) composes the real Noise stack (RelayDialer over CP-rendezvous + relay
 * WS, TOFU trust, operator-auth with the live CP hubTicket); tests inject fakes. `null` in the ViewModel ⇒ INERT.
 */
fun interface RemoteConnectComponentsFactory {
    fun create(hub: HubDescriptor, scope: CoroutineScope): RemoteConnectComponents
}
