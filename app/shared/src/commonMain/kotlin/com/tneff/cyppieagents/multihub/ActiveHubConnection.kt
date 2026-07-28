package com.tneff.cyppieagents.multihub

/**
 * CYP-865 (Compose-M3-Mirror of web-ts CYP-853) — the **switch-first / ONE-ACTIVE-HUB** connection controller
 * (CYP-748-Q4). The active pointer drives EXACTLY ONE live connection behind an **injected** [HubConnector];
 * switching TEARS DOWN the previous hub's connection BEFORE opening the new one, so inactive hubs hold **no** live
 * background connection (resource-safe, CYP-611 sizing; and the precondition for the immediate STALE lapse in
 * [displayedTrust] — no connection ⇒ no observation).
 *
 * **Arming DARK (§9.3).** The [HubConnector] is INJECTED: tests/stubs hand back a fake handle; the **real** dial
 * (socket + statusFeed over the wire) is the ARMING seam (CYP-807-A5), NOT wired here — this controller owns only
 * the ONE-ACTIVE lifecycle, not the transport. The connection's machine type is left generic ([M]): Compose has no
 * `RemoteConnState` machine yet (a separate CYP-822/826-class unit) — the concrete state arrives with M5/arming.
 */

/** A single hub's live connection. [close] tears it down (socket/feed closed, no further reconnect). */
interface HubConnectionHandle<out M> {
    val machine: M
    fun close()
}

/** How to OPEN a live connection for a hub. Real = dial + statusFeed (arming); test/stub = fake-driven. INJECTED. */
fun interface HubConnector<M> {
    fun open(hubId: String): HubConnectionHandle<M>
}

/** The currently-active hub + its machine. */
data class ActiveHub<out M>(val hubId: String, val machine: M)

interface ActiveHubConnection<M> {
    /** The currently active hub + its machine, or null before the first connect. */
    fun active(): ActiveHub<M>?

    /**
     * Make [hubId] the active hub: tear down the previous hub's live connection FIRST (no background connection
     * survives to an inactive hub), THEN open the new one. Switching to the ALREADY-active hub is a **no-op** — the
     * live connection is NOT torn down and re-dialed (mirrors the switcher's switch-to-active no-op).
     */
    fun switchTo(hubId: String)

    /** Tear down the active connection entirely (host unmount). */
    fun close()
}

fun <M> createActiveHubConnection(connector: HubConnector<M>): ActiveHubConnection<M> =
    object : ActiveHubConnection<M> {
        private var current: Pair<String, HubConnectionHandle<M>>? = null

        override fun active(): ActiveHub<M>? =
            current?.let { (hubId, handle) -> ActiveHub(hubId, handle.machine) }

        override fun switchTo(hubId: String) {
            if (current?.first == hubId) return // switch-to-active = no-op (do NOT tear down + re-dial)
            current?.second?.close() // tear down the OLD first — exactly one live connection at a time
            current = hubId to connector.open(hubId)
        }

        override fun close() {
            current?.second?.close()
            current = null
        }
    }
