package com.tneff.cyppieagents.arming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-880 (Epic CYP-832 lane-d, DARK) — teeth for the arming-prereq verification harness. Two things are pinned:
 * (1) the fail-closed aggregation — **INDETERMINATE is its OWN state and NEVER counts as PASS** (the arming gate needs
 * an explicit PASS on every prereq); (2) each verifier is non-vacuous — a positive AND a negative control, plus the
 * unreachable→INDETERMINATE path. P2 is the honest DEFERRED FAIL. No network: verifiers run against stub probes.
 */
class Cyp880ArmingPrereqHarnessTest {

    private fun ev(prereq: String, status: PrereqStatus) = PrereqEvidence(prereq, prereq, status, "$prereq=$status")

    // ── Fail-closed aggregation ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun allPass_isEligible() {
        val report = ArmingPrereqReport(listOf(ev("P1", PrereqStatus.PASS), ev("P2", PrereqStatus.PASS), ev("P3", PrereqStatus.PASS)))
        assertTrue(report.allPassed)
        assertTrue(report.blocking.isEmpty())
    }

    @Test
    fun anyFail_notEligible_andBlocking() {
        val report = ArmingPrereqReport(listOf(ev("P1", PrereqStatus.PASS), ev("P2", PrereqStatus.FAIL), ev("P3", PrereqStatus.PASS)))
        assertFalse(report.allPassed)
        assertEquals(listOf("P2"), report.blocking.map { it.prereq })
    }

    /**
     * ★ CENTRAL TOOTH — **INDETERMINATE ≠ PASS**. A report with an unmeasured prereq is NOT arming-eligible, and the
     * unmeasured prereq is a blocker. **Mutation tooth:** flip `allPassed` to treat INDETERMINATE as pass (e.g.
     * `all { it.status != FAIL }`) and THIS reds — an "unknown" prereq must never silently become a green.
     */
    @Test
    fun indeterminate_isNotEligible_ownState() {
        val report = ArmingPrereqReport(listOf(ev("P1", PrereqStatus.PASS), ev("P2", PrereqStatus.INDETERMINATE), ev("P3", PrereqStatus.PASS)))
        assertFalse(report.allPassed, "an INDETERMINATE prereq must NOT be arming-eligible")
        assertEquals(listOf("P2"), report.blocking.map { it.prereq }, "the INDETERMINATE prereq is a blocker")
    }

    @Test
    fun empty_notEligible() {
        assertFalse(ArmingPrereqReport(emptyList()).allPassed, "no results ⟹ not eligible (fail-closed)")
    }

    @Test
    fun verifierThrows_capturedAsIndeterminate_notPass() {
        val harness = ArmingPrereqHarness(
            listOf(
                "P1" to PrereqVerifier { ev("P1", PrereqStatus.PASS) },
                "boom" to PrereqVerifier { throw IllegalStateException("probe blew up") },
            ),
        )
        val report = harness.run()
        val boom = report.results.single { it.prereq == "boom" }
        assertEquals(PrereqStatus.INDETERMINATE, boom.status, "a throwing verifier is unmeasured, never a pass")
        assertFalse(report.allPassed)
    }

    // ── P1 server-side re-auth: positive + negative + unreachable ──────────────────────────────────────────────────

    private fun probeByBearer(map: Map<String, ProbeResponse?>) = HttpProbe { _, bearer -> map[bearer] }

    @Test
    fun p1_positiveControl_freshGrantsStaleDenied_pass() {
        val v = P1ServerReAuthVerifier(
            probeByBearer(mapOf("fresh" to ProbeResponse(200), "stale" to ProbeResponse(401))),
            freshAal2Bearer = "fresh", staleBearer = "stale",
        )
        assertEquals(PrereqStatus.PASS, v.verify().status)
    }

    @Test
    fun p1_negativeControl_staleNotDenied_fail() {
        // The stale/AAL1 credential is (wrongly) accepted → re-auth NOT enforced → FAIL, even though fresh works.
        val v = P1ServerReAuthVerifier(
            probeByBearer(mapOf("fresh" to ProbeResponse(200), "stale" to ProbeResponse(200))),
            freshAal2Bearer = "fresh", staleBearer = "stale",
        )
        assertEquals(PrereqStatus.FAIL, v.verify().status)
    }

    @Test
    fun p1_unreachable_indeterminate() {
        val v = P1ServerReAuthVerifier(
            probeByBearer(mapOf("fresh" to null, "stale" to ProbeResponse(401))),
            freshAal2Bearer = "fresh", staleBearer = "stale",
        )
        assertEquals(PrereqStatus.INDETERMINATE, v.verify().status)
    }

    // ── P3 god-token loopback close: positive + negative (breach) + invalid-control + unreachable ──────────────────

    @Test
    fun p3_positiveControl_offDeniedOnGranted_pass() {
        val v = P3GodTokenLoopbackVerifier(
            offLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(401) },
            onLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(200) },
            godToken = "god",
        )
        assertEquals(PrereqStatus.PASS, v.verify().status)
    }

    @Test
    fun p3_breach_offLoopbackGrants_fail() {
        // The breach the whole close guards against: off-loopback god-token GRANTS operator.
        val v = P3GodTokenLoopbackVerifier(
            offLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(200) },
            onLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(200) },
            godToken = "god",
        )
        val e = v.verify()
        assertEquals(PrereqStatus.FAIL, e.status)
        assertTrue(e.summary.contains("BREACHED"))
    }

    @Test
    fun p3_invalidControl_onLoopbackDenies_fail() {
        // If the on-loopback control ALSO denies, the off-loopback denial might be an invalid token, not posture → FAIL.
        val v = P3GodTokenLoopbackVerifier(
            offLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(401) },
            onLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(401) },
            godToken = "god",
        )
        assertEquals(PrereqStatus.FAIL, v.verify().status)
    }

    @Test
    fun p3_unreachable_indeterminate() {
        val v = P3GodTokenLoopbackVerifier(
            offLoopbackProbe = HttpProbe { _, _ -> null },
            onLoopbackProbe = HttpProbe { _, _ -> ProbeResponse(200) },
            godToken = "god",
        )
        assertEquals(PrereqStatus.INDETERMINATE, v.verify().status)
    }

    // ── P2 honest DEFERRED FAIL ───────────────────────────────────────────────────────────────────────────────────

    /** P2's binding (CYP-532/G4) is absent → a DEFINITE FAIL. Never PASS (would falsely green arming) and never
     *  INDETERMINATE (the absence is a code fact, not an unmeasurable one). */
    @Test
    fun p2_deferredBinding_isDefiniteFail() {
        val e = P2TunnelCredentialBindingVerifier().verify()
        assertEquals(PrereqStatus.FAIL, e.status)
        assertTrue(e.status != PrereqStatus.PASS && e.status != PrereqStatus.INDETERMINATE)
        assertTrue(e.summary.contains("DEFERRED") || e.summary.contains("CYP-532"))
    }

    /**
     * Integration (network-free): the standard P1/P2/P3 composition — with P1 and P3 mocked GREEN via stub probes —
     * is STILL NOT arming-eligible, because P2 (CYP-532/G4) is the deferred FAIL. This is the intended blocker until
     * CYP-532 lands; it proves a "mostly green" report never slips through on P2's absence.
     */
    @Test
    fun composition_p2DeferredBlocksArming_evenWithP1P3Green() {
        val passOff = HttpProbe { _, _ -> ProbeResponse(401) } // off-loopback denies
        val passOn = HttpProbe { _, _ -> ProbeResponse(200) }  // on-loopback grants
        val p1Probe = probeByBearer(mapOf("fresh" to ProbeResponse(200), "stale" to ProbeResponse(401)))
        val harness = ArmingPrereqHarness(
            listOf(
                "P1" to P1ServerReAuthVerifier(p1Probe, "fresh", "stale"),
                "P2" to P2TunnelCredentialBindingVerifier(),
                "P3" to P3GodTokenLoopbackVerifier(passOff, passOn, "god"),
            ),
        )
        val report = harness.run()
        assertEquals(PrereqStatus.PASS, report.results.single { it.prereq == "P1" }.status)
        assertEquals(PrereqStatus.PASS, report.results.single { it.prereq == "P3" }.status)
        assertEquals(PrereqStatus.FAIL, report.results.single { it.prereq == "P2" }.status)
        assertFalse(report.allPassed, "P2 deferred FAIL blocks arming even with P1+P3 green")
        assertEquals(listOf("P2"), report.blocking.map { it.prereq })
    }
}
