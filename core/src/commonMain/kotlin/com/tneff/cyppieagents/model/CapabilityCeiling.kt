package com.tneff.cyppieagents.model

/**
 * E2.4 / CYP-140 — the trust axis for a connector source. LOCAL = our code, full trust. REMOTE/BYOA =
 * foreign code in foreign infra → **untrusted**: its self-declared capabilities are *data, not authority*.
 * Orthogonal to [ConnectorKind] (realization) and [ProviderInfo] (tool). MVP: every locally-spawned agent
 * is LOCAL; REMOTE enters only via the E2.2 wire (lands dark until then).
 */
enum class ConnectorTrust { LOCAL, REMOTE }

/**
 * E2.4 / CYP-140 — the **reducing-only capability ceiling** (the trust-inversion's genuinely new piece,
 * Doc 12 §3). A connector's self-declared [Capabilities] may only **lower** trust, never raise it:
 * [clamp] takes the per-dimension **most-restrictive** of {declared, ceiling}. [CapabilityStatus] is
 * ordered `AVAILABLE(0) < LIMITED(1) < UNAVAILABLE(2)`, so "most-restrictive" = the higher ordinal.
 *
 * A REMOTE "lie" — claiming AVAILABLE on a ceiling-LIMITED dimension — is clamped to LIMITED, so
 * [CapabilityGate.mode] sees DEGRADED, never ENABLED: lies **degrade, never escalate**. An honest REMOTE
 * reduction (declaring UNAVAILABLE) is respected (the more-restrictive value wins).
 *
 * **LOCAL ceiling = all-AVAILABLE ⇒ clamp is the identity ⇒ local behaviour byte-unchanged** (the CYP-121/122
 * gate suite stays green — the E2-S2 regression guard). Applied **once**, where a connector's caps are
 * recorded (the boot capability loop), so every downstream consumer sees the clamped, server-authoritative
 * caps — single-sourced, no consumer change.
 *
 * **Forward-obligation (reviewer):** the clamp MUST also run wherever E2.2's wire ingests a REMOTE
 * capability handshake — no unclamped remote caps may bypass it.
 */
object CapabilityCeiling {

    /**
     * The trust ceiling the SERVER grants a [trust] source, regardless of what it declares.
     *  - LOCAL → all AVAILABLE (clamp is identity).
     *  - REMOTE → the §1-final profile (clamp what we cannot independently verify, hardest on the
     *    highest-damage dimension): `structuredUsage`=UNAVAILABLE (billing-critical + unverifiable remote
     *    token counts — never trust self-reported numbers), `rateLimitSignal`/`toolGranularity`/
     *    `reliableResult`=LIMITED (degraded-but-not-off observability/stall), `coordination`=AVAILABLE
     *    (the one verifiable dim — the remote uses OUR wire, which we see directly).
     */
    fun ceilingFor(trust: ConnectorTrust): Capabilities = when (trust) {
        ConnectorTrust.LOCAL -> ALL_AVAILABLE
        ConnectorTrust.REMOTE -> REMOTE_CEILING
    }

    /** Effective caps = per-dimension most-restrictive(declared, ceiling). A claim can only LOWER trust.
     *  The connector [Capabilities.kind] is NOT a trust-clamped dimension — it is kept from [declared]. */
    fun clamp(declared: Capabilities, ceiling: Capabilities): Capabilities = Capabilities(
        structuredUsage = mostRestrictive(declared.structuredUsage, ceiling.structuredUsage),
        toolGranularity = mostRestrictive(declared.toolGranularity, ceiling.toolGranularity),
        reliableResult = mostRestrictive(declared.reliableResult, ceiling.reliableResult),
        rateLimitSignal = mostRestrictive(declared.rateLimitSignal, ceiling.rateLimitSignal),
        coordination = mostRestrictive(declared.coordination, ceiling.coordination),
        kind = declared.kind,
    )

    /** Higher ordinal = less trusting (AVAILABLE<LIMITED<UNAVAILABLE) → most-restrictive picks it. */
    private fun mostRestrictive(a: CapabilityStatus, b: CapabilityStatus): CapabilityStatus =
        if (a.ordinal >= b.ordinal) a else b

    private val ALL_AVAILABLE = Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.STREAM_JSON, // ceiling.kind is unused by clamp (declared.kind is kept)
    )

    private val REMOTE_CEILING = Capabilities(
        structuredUsage = CapabilityStatus.UNAVAILABLE, // billing-critical + remote token counts unverifiable
        toolGranularity = CapabilityStatus.LIMITED,
        reliableResult = CapabilityStatus.LIMITED,
        rateLimitSignal = CapabilityStatus.LIMITED, // keep the (degraded) Warden stall net, don't switch it off
        coordination = CapabilityStatus.AVAILABLE, // the one verifiable dim — the remote uses our wire
        kind = ConnectorKind.STREAM_JSON,
    )
}
