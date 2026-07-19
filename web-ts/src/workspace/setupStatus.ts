// CYP-735 (First-Run parity, UIUX2 spec ad741f60 §3.1/§4) — the hub's setup status as a CLOSED UNION.
//
// WHY A UNION AND NOT A NULLABLE. The repo config arrives as `RepoConfigView | null`, where `null` carries TWO
// different meanings: "not loaded yet" and "the load failed". A banner derived from `null` would tell a user whose
// hub is configured to go and set it up — a fabricated fact produced from a missing answer. Same family as CYP-288
// (failed ≠ empty) and CYP-705 (unknown ≠ zero), here on the config surface.
//
// So the honesty sits in the TYPE rather than in each caller's discipline: four distinct states, and the "set up
// your hub" prompt is reachable from exactly ONE of them. There is deliberately no boolean accessor like
// `needsSetup(x)`, because a two-valued answer is precisely the mechanism by which "we don't know" collapses into
// "not configured" — the same reason CYP-705 dropped its two-state accessor.
//
// The error case is NOT merely "no banner": a failed config load has its own honest surface (error + retry, the
// CYP-679 path already wired on this fetch). Silence there would read as "everything is fine".
import type { RepoConfigView } from '../types/generated/contract'

export type SetupStatus =
  /** The config has not answered yet. Honest resting state — never "unconfigured". */
  | { readonly kind: 'unknown' }
  /** The config load FAILED. Distinct from unconfigured: we do not know, and must not guess "set it up". */
  | { readonly kind: 'error' }
  /** The server said `configured: false` — the ONLY state that may prompt setup. */
  | { readonly kind: 'unconfigured' }
  /** The server said `configured: true`. */
  | { readonly kind: 'configured' }

/**
 * Fold the loader's two signals into one honest state.
 *
 * Order matters: a load error wins over an absent config, because `config === null` is exactly what a failed load
 * leaves behind — reading it as "unconfigured" is the defect this type exists to prevent.
 */
export function setupStatusOf(config: RepoConfigView | null, loadError: boolean): SetupStatus {
  if (loadError) return { kind: 'error' } // failed ≠ unconfigured
  if (config === null) return { kind: 'unknown' } // not answered yet ≠ unconfigured
  // CYP-735 F1 (Tester2): a 200 whose body LACKS `configured` must not read as `configured: false`. The REST
  // client casts rather than validates (only the WS boundary validates, CYP-420), so an old server, a proxy error
  // page or a shape change arrives here as `undefined` — and `undefined` is falsy, which silently produced
  // "unconfigured" and put a setup banner on a correctly-configured hub.
  //
  // My original guard was careful about the ABSENCE of an answer (null) but trusted the SHAPE of one that arrived.
  // That is the honesty stopping one level too early: "we got a response" is not "we got an answer". An
  // uninterpretable body is exactly as unknown as no body at all.
  if (typeof config.configured !== 'boolean') return { kind: 'unknown' }
  return config.configured ? { kind: 'configured' } : { kind: 'unconfigured' }
}

/**
 * May we prompt the operator to set the hub up?
 *
 * ONLY on a server-stated `configured: false`. Not while loading, and not after a failed load — prompting there
 * would tell a correctly-configured user to configure, which is worse than staying quiet: it is confidently wrong.
 */
export function mayPromptSetup(status: SetupStatus): boolean {
  return status.kind === 'unconfigured'
}
