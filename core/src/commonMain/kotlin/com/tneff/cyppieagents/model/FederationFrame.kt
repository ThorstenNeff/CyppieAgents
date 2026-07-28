package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CYP-859 (S-Fed-1①, Epic CYP-832) — the sealed hub↔hub federation WIRE frame. Ratified §4b foundation, frozen at
 * the Auftraggeber M2 sign-off. Decoded through [com.tneff.cyppieagents.CommJson] (`classDiscriminator = "type"`,
 * `explicitNulls = false`, `ignoreUnknownKeys = true`) — the same wire idiom as [CommWsServerEvent].
 *
 * **Forward-compat (§4b-4), by construction:** the discriminator is `type`; a variant is added by a new
 * `@SerialName` subtype (never a reorder/repurpose); additive nullable-default fields are OMITTED when null and an
 * unknown field is IGNORED on decode (both via CommJson) — so an older peer tolerates a newer peer's additive
 * fields. An **unknown frame type fails CLOSED** (CommJson throws on an unmapped discriminator — the peer closes,
 * never silently ignores). Still `@ExperimentalFederation` until the whole surface stabilizes: the SHAPE may gain
 * variants; the §4b freeze fixes the discriminator + the initial type-names, not the final variant set.
 */
@ExperimentalFederation
@Serializable
sealed interface FederationFrame

/**
 * The FIRST frame after tunnel establishment (§4b-3): the version-negotiation handshake. Each peer announces the
 * inclusive protocol range `[protoMin, protoMax]` it speaks; [negotiateVersion] picks the highest common version or
 * REFUSES (fail-closed) when the ranges are disjoint. This bounds the flag-day cost — future versions negotiate DOWN
 * instead of forcing a synchronized upgrade.
 */
@ExperimentalFederation
@Serializable
@SerialName("hello")
data class FederationHello(
    val protoMin: Int,
    val protoMax: Int,
) : FederationFrame

/**
 * Negotiate the highest protocol version BOTH peers support: the top of the overlap
 * `[max(localMin, remoteMin) .. min(localMax, remoteMax)]`, i.e. **negotiate-DOWN** to the highest mutually-spoken
 * version. Returns `null` = **REFUSE** (§4b-3 out-of-range = fail-closed) when the ranges are disjoint (including a
 * malformed inverted remote range) — never assume-compatible.
 */
@ExperimentalFederation
fun negotiateVersion(local: FederationHello, remote: FederationHello): Int? {
    val lo = maxOf(local.protoMin, remote.protoMin)
    val hi = minOf(local.protoMax, remote.protoMax)
    return if (lo <= hi) hi else null
}

/**
 * CYP-859 (§4b-1) — the PoP purpose tag for a FEDERATION peer attestation. **Domain-separates** a hub↔hub peer PoP
 * from the OPERATOR device PoP ([com.tneff.cyppieagents.operator.OPERATOR_AUTH_PURPOSE] = "operator-auth"): the
 * federation PoP REUSES the `:core` `operatorAuthChallenge(h, hubId, nonce)` transcript construction (CYP-473,
 * length-prefixed injective, bound to the live Noise `h`) with THIS purpose instead — no new transcript (the
 * CYP-536 C1 lesson). This constant plants the frozen tag; the federation challenge fn that consumes it lands with
 * the peer-handshake wiring. **Not** `@ExperimentalFederation`: the tag string is a ratified §4b-1 freeze, stable.
 */
const val FEDERATION_PEER_PURPOSE = "federation-peer"
