package com.tneff.cyppieagents.arming

/**
 * CYP-880 (Epic CYP-832, lane-d deploy-mechanik, DARK) — the arming-prereq **verification-harness** core: the
 * structured evidence model + fail-closed aggregation that produces the PL-review input for the federation
 * ARMING-GATE (Runbook CYP-872 §2). The harness runs three prereq verifiers (P1 server-side re-auth · P2 §9.3
 * tunnel↔credential binding / G4-CYP-532 · P3 god-token loopback close) against a RUNNING hub on REAL topology and
 * emits per-prereq [PrereqEvidence].
 *
 * **This builds READINESS, it does not arm.** The verifiers probe injected seams (§probe targets supplied at run
 * time), so the harness is unit-testable against stubs and stays inert until an operator/PL invokes it against a real
 * target. Choosing WHICH machines and running it live is Auftraggeber-gated — not this code's initiative.
 */

/**
 * The outcome of one prereq check. **[INDETERMINATE] is its OWN state** — "could not be measured / unreachable /
 * ambiguous" must NEVER collapse into [PASS]. The arming gate requires an EXPLICIT [PASS] on every prereq; a missing
 * or unmeasurable one is a blocker, not a silent green (the safe value is the distinct state, not the happy path —
 * cf. the fail-closed-default discipline).
 */
enum class PrereqStatus { PASS, FAIL, INDETERMINATE }

/**
 * Structured, PL-reviewable evidence for one prereq. [observations] carries the concrete probe results — including
 * the positive AND negative controls — so the PL can audit *why* the status is what it is, not just take the verdict.
 */
data class PrereqEvidence(
    val prereq: String,               // "P1" | "P2" | "P3"
    val title: String,
    val status: PrereqStatus,
    val summary: String,              // one-line human-readable verdict
    val observations: List<String> = emptyList(),
)

/**
 * The aggregate arming-prereq report — the artifact the PL reviews before an arming GO.
 *
 * **Fail-closed:** [allPassed] is `true` **only** when there is at least one result AND EVERY result is an explicit
 * [PrereqStatus.PASS]. Any [FAIL] **or** [INDETERMINATE] — or an empty result set — makes it `false`. INDETERMINATE
 * is deliberately NOT treated as pass: an unmeasured prereq blocks arming exactly like a failed one.
 */
data class ArmingPrereqReport(val results: List<PrereqEvidence>) {
    val allPassed: Boolean
        get() = results.isNotEmpty() && results.all { it.status == PrereqStatus.PASS }

    /** The prereqs that are NOT an explicit PASS (FAIL or INDETERMINATE) — the arming blockers, for the PL summary. */
    val blocking: List<PrereqEvidence>
        get() = results.filter { it.status != PrereqStatus.PASS }
}

/**
 * A single prereq verifier. It probes a running hub through injected seams and returns structured [PrereqEvidence].
 * **DARK:** the probe targets are supplied at construction/run time — a verifier never connects on its own until the
 * harness is invoked against a real target. A verifier that cannot reach or conclusively measure its target returns
 * [PrereqStatus.INDETERMINATE] (never a hopeful [PrereqStatus.PASS]).
 */
fun interface PrereqVerifier {
    fun verify(): PrereqEvidence
}

/**
 * Runs the configured prereq verifiers and aggregates their evidence into an [ArmingPrereqReport]. A verifier that
 * THROWS is captured as an [PrereqStatus.INDETERMINATE] result (fail-closed — a crashing probe is "unmeasured", never
 * a silent pass), so one flaky verifier never aborts the whole readiness read.
 */
class ArmingPrereqHarness(private val verifiers: List<Pair<String, PrereqVerifier>>) {
    fun run(): ArmingPrereqReport = ArmingPrereqReport(
        verifiers.map { (label, v) ->
            runCatching { v.verify() }.getOrElse { t ->
                PrereqEvidence(
                    prereq = label,
                    title = label,
                    status = PrereqStatus.INDETERMINATE,
                    summary = "verifier threw — unmeasured (fail-closed, NOT a pass): ${t.message ?: t::class.simpleName}",
                )
            }
        },
    )
}
