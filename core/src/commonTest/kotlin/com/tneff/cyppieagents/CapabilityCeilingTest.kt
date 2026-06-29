package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityCeiling
import com.tneff.cyppieagents.model.CapabilityGate
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.CapabilityStatus.LIMITED
import com.tneff.cyppieagents.model.CapabilityStatus.UNAVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ConnectorTrust
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E2.4 / CYP-140 (A4) — the reducing-only capability ceiling. Self-report can only LOWER trust; a lie
 * degrades, never escalates; an honest reduction is respected; LOCAL clamp = identity (E2-S2 guard).
 */
class CapabilityCeilingTest {

    private fun caps(
        structuredUsage: com.tneff.cyppieagents.model.CapabilityStatus,
        toolGranularity: com.tneff.cyppieagents.model.CapabilityStatus,
        reliableResult: com.tneff.cyppieagents.model.CapabilityStatus,
        rateLimitSignal: com.tneff.cyppieagents.model.CapabilityStatus,
        coordination: com.tneff.cyppieagents.model.CapabilityStatus,
    ) = Capabilities(structuredUsage, toolGranularity, reliableResult, rateLimitSignal, coordination, ConnectorKind.STREAM_JSON)

    @Test
    fun localCeilingIsIdentity_e2s2Guard() {
        // Forward-obligation #2: clamp(localCaps, ceilingFor(LOCAL)) == localCaps → null local behaviour change.
        val local = caps(AVAILABLE, LIMITED, AVAILABLE, UNAVAILABLE, AVAILABLE) // arbitrary honest local caps
        assertEquals(local, CapabilityCeiling.clamp(local, CapabilityCeiling.ceilingFor(ConnectorTrust.LOCAL)))
        // and an all-AVAILABLE local stays all-AVAILABLE (every gate dim ENABLED — not forced off).
        val allAvail = caps(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE)
        val eff = CapabilityCeiling.clamp(allAvail, CapabilityCeiling.ceilingFor(ConnectorTrust.LOCAL))
        assertEquals(CapabilityGate.CapabilityMode.ENABLED, CapabilityGate.mode(CapabilityGate.EnforcedCapability.TOOL_GRANULARITY, eff))
        assertEquals(CapabilityGate.CapabilityMode.ENABLED, CapabilityGate.mode(CapabilityGate.EnforcedCapability.STRUCTURED_USAGE, eff))
    }

    @Test
    fun remoteCeilingIsTheFinalProfile() {
        val c = CapabilityCeiling.ceilingFor(ConnectorTrust.REMOTE)
        assertEquals(UNAVAILABLE, c.structuredUsage, "billing-critical + unverifiable → OFF")
        assertEquals(LIMITED, c.toolGranularity)
        assertEquals(LIMITED, c.reliableResult)
        assertEquals(LIMITED, c.rateLimitSignal, "keep the degraded Warden stall net, don't switch it off")
        assertEquals(AVAILABLE, c.coordination, "the one verifiable dim — the remote uses our wire")
    }

    @Test
    fun remoteLieDegradesNeverEscalates() {
        // A REMOTE connector claims all-AVAILABLE (the lie). Clamp lowers to the §1 ceiling.
        val lie = caps(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE)
        val eff = CapabilityCeiling.clamp(lie, CapabilityCeiling.ceilingFor(ConnectorTrust.REMOTE))
        assertEquals(UNAVAILABLE, eff.structuredUsage, "claim AVAILABLE on an OFF-ceiling dim → still OFF")
        assertEquals(LIMITED, eff.toolGranularity, "claim AVAILABLE on a LIMITED-ceiling dim → LIMITED")
        // the gate sees DEGRADED/OFF for the lied enforced dims, NEVER ENABLED:
        assertEquals(CapabilityGate.CapabilityMode.DEGRADED, CapabilityGate.mode(CapabilityGate.EnforcedCapability.TOOL_GRANULARITY, eff))
        assertEquals(CapabilityGate.CapabilityMode.OFF, CapabilityGate.mode(CapabilityGate.EnforcedCapability.STRUCTURED_USAGE, eff))
    }

    @Test
    fun honestReductionIsRespected() {
        // An honest REMOTE declares UNAVAILABLE on coordination → stays UNAVAILABLE (more-restrictive wins).
        val honest = caps(UNAVAILABLE, LIMITED, LIMITED, LIMITED, UNAVAILABLE)
        val eff = CapabilityCeiling.clamp(honest, CapabilityCeiling.ceilingFor(ConnectorTrust.REMOTE))
        assertEquals(UNAVAILABLE, eff.coordination, "an honest reduction below the ceiling is respected")
    }

    @Test
    fun honestInCeilingValuesAreUsed_notForcedOff_positiveControl() {
        // M-A4 non-vacuity: prove clamp is NOT a constant-OFF. Under REMOTE, an honest in-ceiling value
        // survives: coordination AVAILABLE stays AVAILABLE, and a LIMITED dim stays LIMITED → DEGRADED (used).
        val honest = caps(LIMITED, LIMITED, LIMITED, LIMITED, AVAILABLE)
        val eff = CapabilityCeiling.clamp(honest, CapabilityCeiling.ceilingFor(ConnectorTrust.REMOTE))
        assertEquals(AVAILABLE, eff.coordination, "honest AVAILABLE within the ceiling is preserved (not forced off)")
        assertEquals(LIMITED, eff.rateLimitSignal)
        assertEquals(
            CapabilityGate.CapabilityMode.DEGRADED,
            CapabilityGate.mode(CapabilityGate.EnforcedCapability.RATE_LIMIT_SIGNAL, eff),
            "honest LIMITED → DEGRADED (used, not OFF)",
        )
    }
}
