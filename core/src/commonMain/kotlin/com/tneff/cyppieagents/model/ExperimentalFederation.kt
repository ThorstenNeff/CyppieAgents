package com.tneff.cyppieagents.model

/**
 * CYP-851 (Epic CYP-832 Multi-Hub federation) — the opt-in marker for **experimental federation API whose shape is
 * not yet frozen** (the §4b wire-envelope + the hub↔hub session/transport seam). Requiring an explicit `@OptIn`
 * keeps a caller from accidentally depending on a shape that will reshape at the §4b byte-freeze — the §5-Naht
 * discipline: build against the STUB seam, never a concrete wire DTO. Downgraded/removed once §4b/§5 are ratified
 * and the federation surface is stable.
 *
 * NB: the CYP-849 trust decision (`FederationTrustDecider` etc.) is deliberately NOT marked — it is pure
 * classification over the already-shipped [HubIssuerTrust] vocabulary and does not reshape at the wire freeze. Only
 * the wire/transport-shaped surface carries this marker.
 */
@RequiresOptIn(
    message = "Federation API is experimental and unfrozen (pending CYP-832 §4b/§5 ratification); its shape may change.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalFederation
