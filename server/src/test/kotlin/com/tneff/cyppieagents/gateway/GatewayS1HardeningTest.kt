package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.contract.RestContract
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-638 S1-hardening — pin the PROPERTY "the `/api/cp` control plane is NEVER reachable through the gateway",
 * independent of *why* it holds today. The pre-hardening edge relied on an emergent, untested invariant: cp-denial held
 * only because no data-plane op had a `{param}` at segment index 2 — a future `Op("GET","/api/{scope}/summary")` would
 * let `/api/cp/summary` match and leak silently.
 *
 * To make the property NON-VACUOUS (and the deny mutation-provable NOW), the allowlist here is built from `REST_OPS`
 * **plus a colliding data-plane twin for each cp op** (`/api/cp/X` → `/api/{scope}/X`): those twins WOULD match the cp
 * paths, so the ONLY thing keeping the control plane out is the request-side cp-deny. Asserted in three views —
 * canonical, `/api/v1`-folded, and `%63p`-encoded. Mutation: drop the request-side deny → every twin matches → every
 * assertion reds.
 */
class GatewayS1HardeningTest {

    private val cpOps = RestContract.REST_OPS.filter { it.path.startsWith(GatewayAllowlist.CONTROL_PLANE_PREFIX) }

    // A data-plane TWIN per cp op with the `cp` segment turned into a `{param}` — it matches the cp path (scope=cp), so
    // only the request-side deny keeps the control plane unreachable. This is exactly the future collision the deny defends.
    private val collidingTwins = cpOps.map { it.copy(path = it.path.replaceFirst("/cp/", "/{scope}/")) }
    private val allow = GatewayAllowlist.fromRestContract(RestContract.REST_OPS + collidingTwins)

    @Test
    fun controlPlaneUnreachable_inAllThreeViews_evenWithCollidingDataPlaneTemplates() {
        assertTrue(cpOps.size >= 6, "sanity: exercised all /api/cp ops (${cpOps.size})")
        // Collect EVERY (op, view) that is wrongly reachable rather than fail-fast on the first — so a mutation reds
        // ALL 3 views visibly at once (6 cp-ops × 3 views = 18), not just the canonical one (the view-split rule).
        val reachable = mutableListOf<String>()
        for (op in cpOps) {
            val method = HttpMethod.parse(op.method)
            val cp = op.path.replace(Regex("\\{[^}]+}"), "x") // e.g. /api/cp/rendezvous/x
            val views = mapOf(
                "canonical" to cp,
                "v1-folded" to "/api/v1" + cp.removePrefix("/api"),
                "%63p-encoded" to cp.replaceFirst("/cp/", "/%63p/"),
            )
            for ((view, path) in views) if (allow.isAllowed(method, path)) reachable += "${op.method} $path [$view]"
        }
        assertTrue(reachable.isEmpty(), "control-plane paths reachable through the gateway (must all be denied): $reachable")
    }

    @Test
    fun sanity_theTwinsActuallyCollide_soTheTestIsNotVacuous() {
        // Prove the fixture is real: WITHOUT the cp-deny a colliding twin WOULD allow the cp path (this is the exact
        // mutation the main test guards). Here we allow the data-plane twin directly (a non-cp path) to show it matches.
        val hubsTwin = collidingTwins.firstOrNull { it.path.endsWith("/hubs") } ?: collidingTwins.first()
        val concrete = hubsTwin.path.replace(Regex("\\{[^}]+}"), "x") // /api/{scope}/hubs → /api/x/hubs (a legit data-plane hit)
        assertTrue(
            allow.isAllowed(HttpMethod.parse(hubsTwin.method), concrete),
            "the colliding twin $concrete is allowed (proves the twin is a real matcher, so the cp-deny is what blocks /api/cp/…)",
        )
    }
}
