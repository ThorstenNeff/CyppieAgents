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
    /** A trusted issuer anchor is established → proceed (no issuer-axis block). */
    data object Trusted : IssuerTrustSignal

    /** Remote issuer trust does not apply (the remote relay is not configured) → proceed. */
    data object NotApplicable : IssuerTrustSignal

    /** Owned hub, but NO trusted issuer anchor → produce the terminal fail-closed block. [issuer] = a hint for logs. */
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
    IssuerTrustSignal.Trusted, IssuerTrustSignal.NotApplicable -> null
}

/**
 * The INERT default issuer check (stub-parallel): always [IssuerTrustSignal.NotApplicable] → the live connect flow is
 * unchanged until the real wire carrier (edge ②) + a real check are wired (the final slice). Deliberately **NOT** a
 * false [IssuerTrustSignal.Trusted]: "not applicable" means the issuer gate simply does not fire yet, NEVER that an
 * untrusted issuer was waved through (fail-closed intent survives the stub).
 */
object InertIssuerCheck : IssuerTrustCheck {
    override suspend fun evaluate(hubId: String): IssuerTrustSignal = IssuerTrustSignal.NotApplicable
}
