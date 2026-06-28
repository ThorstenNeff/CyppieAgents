package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.model.Event

/**
 * One pattern over the event stream → at most one [Signal] (07 §3). A Detector is **pure Sense**: it
 * may hold only *derived* short-term state (e.g. "last activity per agent") that is fully
 * reconstructible from the stream, and it returns `null` when there is no finding.
 *
 * **Structural safety (07 §1/§2):** a Detector is handed only the read-only [Event]; it has **no
 * handle to any agent session, connector or actuator**, so a detector — and the [Scanner] that runs
 * it — *cannot* write toward an agent by construction. Acting on an agent (the nudge) is the
 * Warden/Actuator's exclusive authority in a later slice. "Erkennen ist billig und harmlos (nur
 * lesen)"; the Scanner stays on the harmless side of that line.
 *
 * **Loop-avoidance contract:** because the Scanner subscribes to the *whole* bus and Signals are
 * themselves Events, a Detector MUST ignore its own output types (return `null` for them). The first
 * real detector (CYP-61, `stall.suspected`) reacts only to `error.ratelimit`/activity events, so it
 * never re-triggers on a `stall.*` event it caused.
 */
fun interface Detector {
    fun onEvent(e: Event): Signal?
}
