package com.tneff.cyppieagents.arming

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * CYP-880 — the concrete arming-prereq verifiers + the injected HTTP probe seam. Each verifier is **non-vacuous by
 * construction**: it runs BOTH the positive control (the thing that must succeed) and the negative control (the thing
 * that must be denied), so a PASS cannot be reached by, e.g., an invalid token that merely happens to be rejected.
 * Grounded on the CYP-880 surface map (P1 = AAL2 re-auth `Principal.kt:153`; P3 = `operatorEligible` loopback close
 * `Auth.kt:53`, modeled by `Cyp828GodTokenLoopbackGateE2eTest`; P2 = the DEFERRED CYP-532/G4 binding).
 *
 * **DARK / secret-safe:** verifiers touch the network only through the injected [HttpProbe]; the live [JdkHttpProbe]
 * connects only when a verifier calls it against the operator-supplied target. Evidence records STATUS CODES, never
 * the bearer tokens.
 */

/** A minimal HTTP observation the verifiers reason over (status + body; body kept for the PL evidence, not parsed for control flow). */
data class ProbeResponse(val status: Int, val body: String = "")

/**
 * The injected HTTP probe seam — a verifier reaches the target hub ONLY through this. `null` = UNREACHABLE, which a
 * verifier turns into [PrereqStatus.INDETERMINATE] (never a hopeful PASS). Stubbable for tests; the live impl is
 * [JdkHttpProbe].
 */
fun interface HttpProbe {
    fun get(path: String, bearer: String?): ProbeResponse?
}

private fun evidence(prereq: String, title: String, status: PrereqStatus, summary: String, obs: List<String>) =
    PrereqEvidence(prereq, title, status, summary, obs)

/**
 * P1 — server-side operator RE-AUTH (not inherited). Probes an operator-only route with BOTH a fresh AAL2 credential
 * (positive control — must be GRANTED, 200) and a stale/AAL1 credential (negative control — must be DENIED, 401/403).
 * PASS ⟺ fresh granted ∧ stale denied. If the stale credential is NOT denied, re-auth is not enforced → FAIL. If the
 * target is unreachable → INDETERMINATE. Backed by the AAL2 chokepoint (`Principal.kt:153`).
 */
class P1ServerReAuthVerifier(
    private val probe: HttpProbe,
    private val freshAal2Bearer: String,
    private val staleBearer: String,
    private val operatorRoute: String = DEFAULT_OPERATOR_ROUTE,
) : PrereqVerifier {
    override fun verify(): PrereqEvidence {
        val title = "server-side operator re-auth (AAL2, not inherited)"
        val fresh = probe.get(operatorRoute, freshAal2Bearer)
            ?: return evidence("P1", title, PrereqStatus.INDETERMINATE, "operator route unreachable with the fresh credential — unmeasured (NOT a pass)", emptyList())
        val stale = probe.get(operatorRoute, staleBearer)
            ?: return evidence("P1", title, PrereqStatus.INDETERMINATE, "operator route unreachable with the stale credential — unmeasured (NOT a pass)", emptyList())
        val freshGranted = fresh.status == 200
        val staleDenied = stale.status == 401 || stale.status == 403
        val obs = listOf("fresh AAL2 credential → HTTP ${fresh.status}", "stale/AAL1 credential → HTTP ${stale.status}")
        return when {
            freshGranted && staleDenied ->
                evidence("P1", title, PrereqStatus.PASS, "re-auth enforced: AAL2 granted (200), stale/AAL1 denied (${stale.status})", obs)
            !staleDenied ->
                evidence("P1", title, PrereqStatus.FAIL, "stale/AAL1 credential was NOT denied (${stale.status}) — server-side re-auth not enforced", obs)
            else ->
                evidence("P1", title, PrereqStatus.FAIL, "fresh AAL2 credential did not grant operator (${fresh.status}) — positive control failed", obs)
        }
    }
}

/**
 * P2 — §9.3 tunnel↔credential binding (M2 G4 / CYP-532). **DEFERRED / ABSENT.** The binding tying an RR3-tunnel
 * `operatorId` to the route-verified credential is NOT built: the live federated path is `InertRelayConnector` /
 * `FederationAdmissionGate(federationEnabled=false)`, and `Rr3TunnelGate` pins a STATIC `pinnedOperatorId` with no
 * runtime cross-check (FederationAdmission.kt KDoc; runbook CYP-872 §2 P2). The single-operator floor
 * (`idx_single_operator`) makes the leak *unreachable* today, but the binding itself does not exist. This verifier
 * reports the **honest FAIL** — it does NOT synthesize a pass for a binding that isn't there, and it is deliberately
 * NOT INDETERMINATE (the absence is a definite code fact, not an unmeasurable one). It becomes a real probe when
 * CYP-532 lands.
 */
class P2TunnelCredentialBindingVerifier : PrereqVerifier {
    override fun verify(): PrereqEvidence = evidence(
        "P2",
        "§9.3 tunnel↔credential runtime binding (M2 G4 / CYP-532)",
        PrereqStatus.FAIL,
        "DEFERRED/ABSENT — the CYP-532/G4 binding is not built; arming is blocked until it lands (this is the honest blocker, not a probe failure)",
        listOf(
            "live federated path = InertRelayConnector; FederationAdmissionGate default federationEnabled=false (inert)",
            "Rr3TunnelGate pins a STATIC pinnedOperatorId — no runtime binding of a tunnel operatorId to the route-verified credential",
            "single-operator floor (idx_single_operator) makes the cross-op leak unreachable TODAY, but the required binding is absent",
        ),
    )
}

/**
 * P3 — god-token loopback close (`operatorEligible = isOperator ∧ loopbackPosture`, `Auth.kt:53`). Probes an
 * operator-only route with the SAME god-token from an OFF-loopback vantage (negative control — must be DENIED) and an
 * ON-loopback vantage (positive control — must be GRANTED, proving the denial is posture-driven, not an invalid
 * token). PASS ⟺ off denied ∧ on granted. An off-loopback GRANT is the breach → FAIL. Mirrors
 * `Cyp828GodTokenLoopbackGateE2eTest`.
 */
class P3GodTokenLoopbackVerifier(
    private val offLoopbackProbe: HttpProbe,
    private val onLoopbackProbe: HttpProbe,
    private val godToken: String,
    private val operatorRoute: String = DEFAULT_OPERATOR_ROUTE,
) : PrereqVerifier {
    override fun verify(): PrereqEvidence {
        val title = "god-token loopback close (operatorEligible = isOperator ∧ loopbackPosture)"
        val off = offLoopbackProbe.get(operatorRoute, godToken)
            ?: return evidence("P3", title, PrereqStatus.INDETERMINATE, "off-loopback vantage unreachable — unmeasured (NOT a pass)", emptyList())
        val on = onLoopbackProbe.get(operatorRoute, godToken)
            ?: return evidence("P3", title, PrereqStatus.INDETERMINATE, "on-loopback control vantage unreachable — unmeasured (NOT a pass)", emptyList())
        val offDenied = off.status == 401 || off.status == 403
        val onGranted = on.status == 200
        val obs = listOf("off-loopback god-token → HTTP ${off.status}", "on-loopback god-token → HTTP ${on.status}")
        return when {
            offDenied && onGranted ->
                evidence("P3", title, PrereqStatus.PASS, "loopback close holds: off-loopback DENIED (${off.status}), on-loopback GRANTED (200) — denial is posture-driven", obs)
            !offDenied ->
                evidence("P3", title, PrereqStatus.FAIL, "off-loopback god-token GRANTED operator (${off.status}) — loopback close BREACHED", obs)
            else ->
                evidence("P3", title, PrereqStatus.FAIL, "on-loopback control did not grant (${on.status}) — cannot confirm the off-loopback denial is posture-driven vs an invalid token", obs)
        }
    }
}

const val DEFAULT_OPERATOR_ROUTE: String = "/api/workspace/members"

/**
 * The operator-supplied target for a live harness run. Secrets (tokens) are consumed at run time and NEVER written to
 * the evidence — the verifiers record only status codes. Choosing WHICH target/machines this points at is
 * Auftraggeber-gated; this type just carries what the operator provides.
 */
data class ArmingTargetConfig(
    val offLoopbackBaseUrl: String,
    val onLoopbackBaseUrl: String,
    val godToken: String,
    val freshAal2Bearer: String,
    val staleBearer: String,
    val operatorRoute: String = DEFAULT_OPERATOR_ROUTE,
)

/**
 * Assemble the standard P1/P2/P3 harness for a live [config]. **Ready-to-run, but does NOT run** — the caller (an
 * operator/PL, on real topology, after an Auftraggeber GO) invokes `.run()`. Building this connects to nothing.
 */
fun buildStandardHarness(config: ArmingTargetConfig): ArmingPrereqHarness {
    val off = JdkHttpProbe(config.offLoopbackBaseUrl)
    val on = JdkHttpProbe(config.onLoopbackBaseUrl)
    return ArmingPrereqHarness(
        listOf(
            "P1" to P1ServerReAuthVerifier(off, config.freshAal2Bearer, config.staleBearer, config.operatorRoute),
            "P2" to P2TunnelCredentialBindingVerifier(),
            "P3" to P3GodTokenLoopbackVerifier(off, on, config.godToken, config.operatorRoute),
        ),
    )
}

/**
 * Live HTTP probe over the JDK client — synchronous, no new deps. **DARK:** it performs a GET only when a verifier
 * calls it against the operator-supplied [baseUrl]; it never connects on its own. Any I/O failure (unreachable,
 * timeout, malformed) → `null` → the verifier yields INDETERMINATE (fail-closed, never a hopeful PASS).
 */
class JdkHttpProbe(private val baseUrl: String, private val timeoutMs: Long = 5_000) : HttpProbe {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()

    override fun get(path: String, bearer: String?): ProbeResponse? = runCatching {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        ProbeResponse(resp.statusCode(), resp.body() ?: "")
    }.getOrNull()
}
