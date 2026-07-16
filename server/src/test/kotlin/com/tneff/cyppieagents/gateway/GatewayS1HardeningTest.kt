package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.contract.RestContract
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
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
        for (op in cpOps) {
            val method = HttpMethod.parse(op.method)
            val cp = op.path.replace(Regex("\\{[^}]+}"), "x") // e.g. /api/cp/rendezvous/x
            assertFalse(allow.isAllowed(method, cp), "canonical: ${op.method} $cp must be denied")
            assertFalse(allow.isAllowed(method, "/api/v1" + cp.removePrefix("/api")), "v1-folded: ${op.method} $cp must be denied")
            assertFalse(allow.isAllowed(method, cp.replaceFirst("/cp/", "/%63p/")), "%63p-encoded: ${op.method} $cp must be denied")
        }
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
