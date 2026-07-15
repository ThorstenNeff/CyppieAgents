package com.tneff.cyppieagents.mux

/**
 * CYP-620 — the shared `:core` per-stream **flow-control window state-machine** (yamux spec v0). This is where G1–G4
 * (credit-deadlock / fairness / init-credit / writer-HoL) come **proven from the spec** rather than invented. Pure
 * logic (no I/O, no platform deps) → common Kotlin, compiler-identical on hub + client (see [YamuxFrame]).
 *
 * Two independent halves per stream:
 *  - [YamuxSendWindow] — how many bytes WE may send (credit the peer granted us). A sender may only send `DATA` while
 *    it holds credit; when it hits 0 it blocks (per-stream backpressure — only THIS stream, never the tunnel).
 *  - [YamuxRecvWindow] — how much unread data WE are holding; it returns a `WINDOW_UPDATE` **delta** as the app
 *    **DRAINS** (not as it enqueues) → **deadlock-free**: a sender never waits on its own un-drained buffer.
 *
 * Initial window = [DEFAULT_INITIAL_WINDOW] (256 KB, our envelope — NOT yamux's 64 MB default; single-sourced config).
 */

/** SEND-side flow control: the credit the peer has granted us for this stream's outbound `DATA`. */
class YamuxSendWindow(initial: Long = DEFAULT_INITIAL_WINDOW) {
    private var credit: Long = initial

    init { require(initial in 0..YamuxFrame.U32_MAX) { "initial send window out of u32 range: $initial" } }

    /** Bytes we may currently send. When 0 → block this stream's pump (backpressure), never the tunnel. */
    fun available(): Long = credit

    /** Consume [n] send credit for a `DATA` frame we are about to send. Caller must ensure `available() >= n`. */
    fun consume(n: Long) {
        require(n in 0..credit) { "send-window underflow: consume $n of $credit" }
        credit -= n
    }

    /** The peer granted [delta] more credit (an inbound `WINDOW_UPDATE`). Capped at u32 (a grant beyond u32 is a
     *  protocol violation the caller fails closed on). */
    fun grant(delta: Long) {
        if (delta < 0) throw YamuxProtocolException("negative WINDOW_UPDATE delta: $delta")
        val next = credit + delta
        if (next > YamuxFrame.U32_MAX) throw YamuxProtocolException("send-window overflow: $credit + $delta > u32")
        credit = next
    }
}

/**
 * RECEIVE-side flow control (the yamux window-update algorithm). We advertise a window to the peer; it spends it by
 * sending `DATA`; as our app drains the buffer we return credit via `WINDOW_UPDATE`. Returning on DRAIN (not enqueue)
 * is what keeps it deadlock-free.
 */
class YamuxRecvWindow(private val maxWindow: Long = DEFAULT_INITIAL_WINDOW) {
    private var buffered: Long = 0            // received-but-not-yet-drained
    private var advertised: Long = maxWindow  // window the peer currently holds from us (our last-announced credit)

    init { require(maxWindow in 1..YamuxFrame.U32_MAX) { "maxWindow out of range: $maxWindow" } }

    fun buffered(): Long = buffered
    fun advertised(): Long = advertised

    /** [n] bytes of `DATA` arrived on this stream. Throws (protocol error → caller fails closed) if the peer OVERSHOT
     *  the window it was granted — the flow-control invariant a malicious/buggy peer must not be able to break. */
    fun onReceived(n: Long) {
        require(n >= 0)
        if (n > advertised) throw YamuxProtocolException("recv-window overshoot: received $n > advertised $advertised")
        buffered += n
        advertised -= n
    }

    /**
     * The app DRAINED [n] bytes (delivered them to the loopback/route). Returns the `WINDOW_UPDATE` **delta** to send
     * back to the peer, or `0` if no update is due yet. The yamux rule: only announce when the newly-available window
     * exceeds half the max (avoids a storm of tiny updates), or when forced ([syn], at stream open).
     */
    fun onDrained(n: Long, syn: Boolean = false): Long {
        require(n in 0..buffered) { "drain $n exceeds buffered $buffered" }
        buffered -= n
        val available = maxWindow - buffered
        if (available > maxWindow / 2 || syn) {
            val delta = available - advertised
            if (delta > 0) {
                advertised += delta
                return delta
            }
        }
        return 0
    }
}

/** The initial/default per-stream window: 256 KB (our DoS envelope; retunable, single-sourced). */
const val DEFAULT_INITIAL_WINDOW: Long = 256L * 1024
