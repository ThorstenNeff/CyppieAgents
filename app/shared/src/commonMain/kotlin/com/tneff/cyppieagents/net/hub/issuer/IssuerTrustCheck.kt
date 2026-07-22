package com.tneff.cyppieagents.net.hub.issuer

import com.tneff.cyppieagents.net.hub.remote.RemoteFailure

/**
 * CYP-802 (CYP-747 S1c, the CLIENT-PRODUCE edge) — the **axis-c issuer-trust seam**. Determines whether the remote
 * hub's CP-JWT/cert ISSUER anchor is trusted; an owned hub with NO trusted issuer grants **no operator authority**
 * ([RemoteFailure.IssuerNotTrusted] — terminal, fail-closed, OOB-only recovery). A THIRD trust axis, orthogonal to
 * (a) hub-key TOFU and (b) operator identity.
 *
 * ★ **STUB-PARALLEL boundary (PO-ratified).** This is the CLIENT half only. The signal it returns ([IssuerTrustSignal])
 * is a **PROVISIONAL client-local carrier — NOT the real `:core` wire type.** The server→client wire crossing (S1c
 * "edge ②") is a **held joint Team-1/Team-2 decision**, so this seam is deliberately NOT wired to a real determination
 * yet: the default [InertIssuerCheck] never fires, and the live connect flow is byte-identical to today. The final
 * slice swaps in a real check fed by that wire carrier (and this stub is the swap point). Axis-c ONLY — must never
 * reference axis-a (hub-key TOFU) or axis-b (operator-identity) types (the Cyp443 separation guard scans this file).
 */
fun interface IssuerTrustCheck {
    suspend fun evaluate(hubId: String): IssuerTrustSignal
}

/**
 * The PROVISIONAL client-local issuer-trust carrier — a **stub** standing in for the real `:core` wire type of S1c
 * edge ②. It mirrors the server's `RemoteIssuerTrustState` meaning (`REMOTE_NOT_CONFIGURED` / `ISSUER_NOT_TRUSTED` /
 * `ISSUER_TRUSTED`) client-side WITHOUT any `:server` coupling. Only an explicit [NotTrusted] ever produces a failure;
 * everything else proceeds (see [toRemoteFailure]).
 */
sealed interface IssuerTrustSignal {
    /** A trusted issuer anchor is established → proceed (no issuer-axis block). Maps from `HubIssuerTrust.TRUSTED`. */
    data object Trusted : IssuerTrustSignal

    /** Remote issuer trust does not apply (the remote relay is not configured) → proceed. Maps from
     *  `HubIssuerTrust.REMOTE_NOT_CONFIGURED`. */
    data object NotApplicable : IssuerTrustSignal

    /**
     * CYP-804 — the issuer verdict is ABSENT/unknown (an old server that does not emit `HubDescriptor.issuerTrust`,
     * i.e. the carrier is `null`). A DISTINCT state, NOT folded into [Trusted] ([[safe-but-silent-default-needs-own-state]]):
     * it must NEVER render a positive "issuer-trusted" affirmation. It PROCEEDS (not a block) — blocking on absence
     * would break old-server connects and is redundant: the real gate is server-side (`InertRelayConnector`), so an
     * actually-untrusted issuer already fails closed there. Only an EXPLICIT [NotTrusted] drives the UI hard block.
     */
    data object Unknown : IssuerTrustSignal

    /** Owned hub, but NO trusted issuer anchor → produce the terminal fail-closed block. [issuer] = a hint for logs.
     *  Maps from `HubIssuerTrust.NOT_TRUSTED`. */
    data class NotTrusted(val issuer: String?) : IssuerTrustSignal
}

/**
 * CYP-802 — the client-produce mapping: an issuer-trust carrier → the [RemoteFailure] to surface, or `null` to
 * proceed. Mirrors the typed-cause translator pattern (`RendezvousUnavailable.toRemoteFailure()`). ONLY
 * [IssuerTrustSignal.NotTrusted] produces a failure ([RemoteFailure.IssuerNotTrusted]); trusted / not-applicable
 * proceed. **This is THE construction site the CYP-797 render arm was waiting for** — before this, `IssuerNotTrusted`
 * had a full render arm but zero producers (a dead, unreachable UI tree).
 */
fun IssuerTrustSignal.toRemoteFailure(): RemoteFailure? = when (this) {
    is IssuerTrustSignal.NotTrusted -> RemoteFailure.IssuerNotTrusted(issuer)
    IssuerTrustSignal.Trusted, IssuerTrustSignal.NotApplicable, IssuerTrustSignal.Unknown -> null
}

/**
 * CYP-804 SWAP MAP (prep — apply when Backend's carrier lands on develop). The PL-frozen `:core` carrier is
 * `HubDescriptor.issuerTrust: HubIssuerTrust? = null` with `enum HubIssuerTrust { TRUSTED, NOT_TRUSTED,
 * REMOTE_NOT_CONFIGURED }`. The real [IssuerTrustCheck] reads that field off the connecting hub's descriptor and maps
 * it here (mirrors this exact table); `issuerHint` is an OPTIONAL log-only hint (the UI copy is static). This is the
 * whole client-produce swap — the [RemoteHubSession] arm + teeth are already in place, only [InertIssuerCheck] is
 * replaced by an impl that returns `descriptor.issuerTrust.toIssuerTrustSignal(hint)`:
 *
 *   HubIssuerTrust.NOT_TRUSTED            -> IssuerTrustSignal.NotTrusted(issuerHint)   // the terminal block
 *   HubIssuerTrust.TRUSTED               -> IssuerTrustSignal.Trusted                   // proceed
 *   HubIssuerTrust.REMOTE_NOT_CONFIGURED -> IssuerTrustSignal.NotApplicable             // proceed
 *   null (absent — old server)           -> IssuerTrustSignal.Unknown                   // proceed, NEVER affirm
 *
 * ★ PL-0107 forward-flag (NOT this edge): a positive `HubIssuerTrust.TRUSTED` render (axis c) must stay DISTINCT from
 * axis-a `HubTrustState.TRUSTED` (neutral, CYP-803) — two "TRUSTED", two axes. My produce arm renders no TRUSTED (it
 * only blocks on NOT_TRUSTED, amber-WARN), so it is unaffected; this is a note for the CYP-803 render side.
 *
 * The commented signature below is the swap's one net-new symbol (kept OUT of compile until HubIssuerTrust exists):
 *   fun HubIssuerTrust?.toIssuerTrustSignal(issuerHint: String?): IssuerTrustSignal = when (this) {
 *       HubIssuerTrust.NOT_TRUSTED -> IssuerTrustSignal.NotTrusted(issuerHint)
 *       HubIssuerTrust.TRUSTED -> IssuerTrustSignal.Trusted
 *       HubIssuerTrust.REMOTE_NOT_CONFIGURED -> IssuerTrustSignal.NotApplicable
 *       null -> IssuerTrustSignal.Unknown
 *   }
 */

/**
 * The INERT default issuer check (stub-parallel): always [IssuerTrustSignal.Unknown] → the live connect flow is
 * unchanged until the real wire carrier (CYP-804) + a real check are wired (the final slice; see the SWAP MAP above).
 * `Unknown` is the honest stub-phase value — the client has NO issuer determination wired yet, so it proceeds WITHOUT
 * affirming trust; deliberately **NOT** a false [IssuerTrustSignal.Trusted]. This object is the sole swap point:
 * `buildRemoteHubSession` injects a real check (reading `HubDescriptor.issuerTrust`) in its place.
 */
object InertIssuerCheck : IssuerTrustCheck {
    override suspend fun evaluate(hubId: String): IssuerTrustSignal = IssuerTrustSignal.Unknown
}
