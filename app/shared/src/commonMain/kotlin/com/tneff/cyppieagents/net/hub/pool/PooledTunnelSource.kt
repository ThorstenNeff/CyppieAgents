package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-537 (M2 Option A, WS2) — the **pooling** source of workspace tunnels: the N-tunnel swap point that fixes
 * F-M2-1. Each [acquire] establishes a **distinct, live, PoP-authenticated** [NoiseTunnel] over its own
 * rendezvous-id (via [PoolTunnelDialer]), up to [cap] ([TUNNEL_POOL_CAP]). The pool OWNS tunnel lifecycle + the cap
 * and emits the C3 [state]; the transport is a pure consumer (it RSTs on a `null` acquire and closes only its
 * acceptor, never the pool's tunnels — the C2 invariants now hold **per tunnel**).
 *
 * **Why N tunnels:** one [NoiseTunnel] = one ordered duplex stream (no mux, spec §5). The CONNECTED mode-blind
 * workspace (CYP-411) opens ~8 eager persistent WS + per-agent WS; single-flight deadlocks on the first. Handing a
 * **fresh** tunnel per concurrent loopback connection gives true live remote streaming.
 *
 * **Contract invariants (C2):**
 *  - `acquire()` returns a distinct tunnel per call, up to [cap]; **at cap ⇒ `null`** (fail-closed — the transport
 *    RSTs; a persistent cap-hit under legitimate load means bump [TUNNEL_POOL_CAP], see its KDoc);
 *  - a dial failure ⇒ `null` (the slot is released, no phantom entry) — never a local/plaintext fallback;
 *  - the pool owns tunnel lifecycle: a connection ending closes ITS tunnel ([PooledNoiseTunnel.close] frees the slot);
 *    [close] tears down all live tunnels (a Q5 switch/leave, nothing carried across).
 *
 * **Concurrency:** dials run **off-lock** (the [mutex] guards only the rendezvous-id counter; the cap-gate + all
 * state transitions are lock-free CAS on [_state]), so N tunnels establish **concurrently** — the whole point vs. a
 * serialized single-flight source.
 *
 * @param dialer establishes one authenticated tunnel over a given rendezvous-id (the real [NoisePoolTunnelDialer]).
 * @param cap the pool cap (default [TUNNEL_POOL_CAP]) — the aggregate bound (H7 §5).
 * @param nowMs the clock for [TunnelStatus.sinceTs] (injected so tests are deterministic; jvm wires `currentTimeMillis`).
 */
class PooledTunnelSource(
    private val dialer: PoolTunnelDialer,
    private val cap: Int = TUNNEL_POOL_CAP,
    private val nowMs: () -> Long,
) {
    private val _state = MutableStateFlow(TunnelPoolState.empty(cap))
    /** The C3 per-tunnel pool state (WS5 renders, WS4 asserts). */
    val state: StateFlow<TunnelPoolState> = _state.asStateFlow()

    /** Guards the ONE-TIME set resolve + the select-a-free-id reservation (so two reserves never pick the same id). */
    private val mutex = Mutex()
    /** The CP-derived opaque rendezvous-id set (C4), resolved once + cached; `null` until the first successful resolve. */
    private var resolvedSet: List<String>? = null

    /** Live wrappers by rendezvous-id — so [close] can tear them all down (the pool owns tunnel lifecycle). */
    private val live = mutableMapOf<String, PooledNoiseTunnel>()
    private val liveLock = Mutex()

    private var closed = false

    /**
     * Establish + return a distinct authenticated tunnel for a new workspace connection, or `null` fail-closed (at
     * [cap], on a dial failure, or after [close]). The returned tunnel is a [PooledNoiseTunnel] — closing it (the
     * bridge does, when the connection ends) frees its pool slot.
     */
    suspend fun acquire(): NoiseTunnel? {
        if (closed) return null
        val rendezvousId = reserveSlot() ?: return null // at cap ⇒ fail-closed (no dial), C2
        val tunnel = try {
            dialer.dial(rendezvousId) // OFF-lock: N dials proceed concurrently
        } catch (t: Throwable) {
            releaseSlot(rendezvousId) // a throw (incl. cancellation) releases the reserved slot before propagating
            throw t
        }
        if (tunnel == null) {
            releaseSlot(rendezvousId) // fail-closed dial ⇒ drop the reservation (no phantom UP entry)
            return null
        }
        if (closed) { // a teardown raced the dial — don't hand out a tunnel the pool won't track/close
            runCatching { tunnel.close() }
            releaseSlot(rendezvousId)
            return null
        }
        markUp(rendezvousId)
        val wrapper = PooledNoiseTunnel(delegate = tunnel, rendezvousId = rendezvousId, pool = this)
        liveLock.withLock { live[rendezvousId] = wrapper }
        return wrapper
    }

    /** Q5 teardown: close every live tunnel (nothing carried across a hub switch/leave). Idempotent. */
    suspend fun close() {
        closed = true
        val snapshot = liveLock.withLock { live.values.toList().also { live.clear() } }
        snapshot.forEach { runCatching { it.close() } }
    }

    // --- C3 state transitions (lock-free CAS; DOWN entries pruned at the next reserve so cap-gating stays honest) ---

    /**
     * Reserve a free slot: resolve the CP N-set once (fail-closed to `null`), then pick an opaque id from the set
     * NOT currently held (C4 — the pool owns count/lifecycle, the ids stay CP-derived), and add its DIALING entry.
     * `null` at cap (all set ids in use) or if the set is unresolved. Serialized by [mutex] so two concurrent
     * reserves never pick the same id; the subsequent dial runs OFF this lock (concurrent establishment).
     */
    private suspend fun reserveSlot(): String? = mutex.withLock {
        val set = resolvedSet ?: dialer.rendezvousSet()?.also { resolvedSet = it } ?: return@withLock null
        val held = _state.value.tunnels.filter { it.state != TunnelState.DOWN } // live/dialing hold a slot
        if (held.size >= cap) return@withLock null // at cap ⇒ no reservation (even if the set has more ids than cap)
        val inUse = held.map { it.rendezvousId }.toSet()
        val freeId = set.firstOrNull { it !in inUse } ?: return@withLock null // set exhausted (≤ cap ids)
        _state.update { cur ->
            val liveTunnels = cur.tunnels.filter { it.state != TunnelState.DOWN } // prune leftover DOWN (freed slots)
            cur.copy(tunnels = liveTunnels + TunnelStatus(freeId, TunnelState.DIALING, nowMs())).recomputeAggregate()
        }
        freeId
    }

    private fun releaseSlot(rendezvousId: String) =
        _state.update { it.copy(tunnels = it.tunnels.filterNot { s -> s.rendezvousId == rendezvousId }).recomputeAggregate() }

    private fun markUp(rendezvousId: String) = _state.update { cur ->
        cur.mapTunnel(rendezvousId) { it.copy(state = TunnelState.UP, sinceTs = nowMs()) }.recomputeAggregate()
    }

    /** H7 (CYP-535): a full/drained send-window toggles a LIVE tunnel between UP↔BACKPRESSURED (never touches DOWN). */
    internal fun onBackpressured(rendezvousId: String, active: Boolean) = _state.update { cur ->
        cur.mapTunnel(rendezvousId) { s ->
            if (s.state == TunnelState.DOWN) s
            else s.copy(state = if (active) TunnelState.BACKPRESSURED else TunnelState.UP, sinceTs = nowMs())
        }.recomputeAggregate()
    }

    /** A connection ended (or a relay drop): mark the tunnel DOWN (one observable emission) + free its slot. The DOWN
     *  entry is pruned at the next [reserveSlot]; the live-map ref is dropped by the wrapper's [PooledNoiseTunnel.close]. */
    internal fun onTunnelClosed(rendezvousId: String) = _state.update { cur ->
        cur.mapTunnel(rendezvousId) { it.copy(state = TunnelState.DOWN, sinceTs = nowMs()) }.recomputeAggregate()
    }

    /** Drop a self-closed wrapper from the live map so [close] won't re-close it (idempotent). */
    internal suspend fun removeLive(rendezvousId: String) = liveLock.withLock { live.remove(rendezvousId); Unit }

    private fun TunnelPoolState.mapTunnel(id: String, f: (TunnelStatus) -> TunnelStatus): TunnelPoolState =
        copy(tunnels = tunnels.map { if (it.rendezvousId == id) f(it) else it })

    private fun TunnelPoolState.recomputeAggregate(): TunnelPoolState = copy(
        aggregate = TunnelPoolAggregate(
            active = tunnels.count { it.state == TunnelState.UP || it.state == TunnelState.BACKPRESSURED },
            cap = cap,
            anyBackpressured = tunnels.any { it.state == TunnelState.BACKPRESSURED },
        ),
    )
}

/**
 * CYP-537 — the pooled tunnel handed to the transport: delegates the [NoiseTunnel] byte ops, and on [close] frees
 * its pool slot (so the bridge closing it at connection-end is how the pool learns the slot is reusable). Implements
 * [BackpressureSignal] so the H7 pump can flag a full send-window as C3 [TunnelState.BACKPRESSURED] on THIS tunnel.
 * [close] is idempotent (the up-pump finally, a relay-drop, and Q5 teardown may all reach it).
 */
class PooledNoiseTunnel internal constructor(
    private val delegate: NoiseTunnel,
    val rendezvousId: String,
    private val pool: PooledTunnelSource,
) : NoiseTunnel, BackpressureSignal {

    override val handshakeHash: ByteArray get() = delegate.handshakeHash
    override suspend fun send(plaintext: ByteArray) = delegate.send(plaintext)
    override suspend fun receive(): ByteArray? = delegate.receive()

    private var closedOnce = false

    override suspend fun close() {
        if (closedOnce) return
        closedOnce = true
        pool.onTunnelClosed(rendezvousId) // mark DOWN + free the slot BEFORE closing the bytes (state-first)
        runCatching { delegate.close() }
        pool.removeLive(rendezvousId)
    }

    override fun onBackpressured(active: Boolean) = pool.onBackpressured(rendezvousId, active)
}
