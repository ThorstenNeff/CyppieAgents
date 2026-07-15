package com.tneff.cyppieagents.net.hub.mux

/**
 * CYP-620 (client wiring) — the **stream class** carried in the first SYN-payload byte (pinned contract; `:core`'s
 * `YamuxFrame` is class-agnostic, so this app-layer convention lives here, client-side). It gives the hub a QoS/lane
 * priority per stream over the ONE shared tunnel — the CYP-616 lane concept, now per-stream. The class is advisory
 * for priority; the stream then carries raw HTTP/WS bytes unchanged.
 *
 * The **4 classes are load-bearing** (PO1 decision): the control-frame non-starvation guarantee needs
 * [CONTROL] (lifecycle stop/start) to be distinguishable from [REST] (e.g. avatar bulk) *within* the REST leg, and
 * [AGENT_WS] from [SINGLETON_WS] within the WS leg — a coarse 2-class collapse would let lifecycle starve behind bulk.
 */
enum class StreamClass(val wire: Int) {
    CONTROL(0),      // control / lifecycle (stop/start/restart/reprovision) — reserved priority (CYP-616 lineage)
    AGENT_WS(1),     // a per-agent `/ws/agent` socket
    SINGLETON_WS(2), // a shared singleton WS (comm/events/acl/busy/token-usage/terminal-state/lifecycle-status/capacity)
    REST(3);         // any other (non-lifecycle) REST

    val wireByte: Byte get() = wire.toByte()

    companion object {
        /** The class for a first-SYN-byte [wire], or `null` if unknown (the hub/peer fails closed, never guesses). */
        fun fromWire(wire: Int): StreamClass? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * CYP-620 — the seam that decides a connection's [StreamClass] (PO1: source = **(a) 4-acceptor/4-port**; the transport
 * knows the class by which loopback port accepted, and passes it here). Abstracted so the session/`MuxedStreamSource`
 * build against the class directly while the 4-acceptor wiring lands as a focused follow-on step — a swap of the
 * classifier impl, not the contract.
 */
fun interface StreamClassifier {
    /** The class for the connection identified by [portTag] (the 4-acceptor port role), or a fail-safe default. */
    fun classify(portTag: String): StreamClass
}
