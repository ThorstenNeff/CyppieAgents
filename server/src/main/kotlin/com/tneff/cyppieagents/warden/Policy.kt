package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.scanner.Signal

/**
 * The **Decide** half of the Mediator-Aufsicht (07 §3). One Policy per signal type carries the real,
 * touchy state — backoff timers, attempt counters, the incident lifecycle per agent — and decides
 * *whether and how* to act on a [Signal], acting only through the [Actuator]. A Policy never touches
 * an agent session directly: the only write authority it is handed is the [Actuator] passed to
 * [onSignal], so "kein Schreibpfad am Actuator vorbei" holds structurally.
 *
 * This is the scaffold seam (CYP-62). The first concrete one, the stall policy (backoff / attempt
 * limit N / recovery / escalation), is CYP-63 — it slots in here as a new registered Policy with no
 * change to the Warden frame.
 */
interface Policy {
    /** True if this policy handles signals of [signalType] (e.g. `"stall.suspected"`). */
    fun handles(signalType: String): Boolean

    /** Decide + act on [signal], using [act] as the sole channel to affect an agent or the PO. */
    suspend fun onSignal(signal: Signal, act: Actuator)
}
