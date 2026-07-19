// CYP-735 §3.3 (UIUX2 screen-spec 12924c37) — the repo clone lifecycle: what the server observed, plus what the
// CLIENT measured, kept strictly apart.
//
// THE ONE RULE THAT SHAPES EVERYTHING HERE: the client never invents a phase. The server reports what it knows
// (`cloneStatus`); the client may only add what it can honestly MEASURE — how long a clone has been running. That
// is why "taking longer" is an ATTRIBUTE of CLONING and not a phase of its own, and why elapsed time never becomes
// "failed": a hung clone and a slow clone are indistinguishable from here, so calling it failed after N seconds
// would be a fabricated diagnosis. Long-running stays "still running, duration unknown".
//
// `cloneStatus` absent/null ⇒ UNKNOWN, never a quiet OK. That covers "still loading", "old server without the
// field", and "the config read failed" — all cases where we do not know, and none where we may reassure.
import type { RepoConfigView } from '../types/generated/contract'

export type CloneStatus = NonNullable<RepoConfigView['cloneStatus']>
export type CloneFailReason = NonNullable<RepoConfigView['cloneFailReason']>

/** What the repo step shows. `unknown` is first-class — the honest resting state, never a silent OK. */
export type CloneView =
  | { readonly kind: 'unknown' }
  | { readonly kind: 'notConfigured' }
  | { readonly kind: 'configuredNeverCloned' }
  /** `elapsedMs` is CLIENT-measured; `slow`/`long` are attributes of this phase, never phases themselves. */
  | { readonly kind: 'cloning'; readonly slow: boolean; readonly long: boolean; readonly elapsedMs: number }
  | { readonly kind: 'failed'; readonly reason: CloneFailReason }
  | { readonly kind: 'ok' }

/** ~15s: advisory "this is taking longer than usual". Still cloning. */
export const CLONE_SLOW_MS = 15_000
/** ~60s: show the elapsed duration and thin the poll. Still cloning. */
export const CLONE_LONG_MS = 60_000
/** Poll cadence while a clone is active, and after the long threshold. Thinning is not giving up. */
export const CLONE_POLL_MS = 2_000
export const CLONE_POLL_THINNED_MS = 30_000

/**
 * Fold the server status + the client-measured elapsed time into what to render.
 *
 * `elapsedMs` is only consulted for CLONING, and only to set advisory attributes. No amount of elapsed time can
 * move this to `failed` or `ok` — those come from the server or not at all.
 */
export function cloneView(config: RepoConfigView | null, elapsedMs: number): CloneView {
  const status = config?.cloneStatus
  if (config === null || status === null || status === undefined) return { kind: 'unknown' }
  switch (status) {
    case 'NOT_CONFIGURED':
      return { kind: 'notConfigured' }
    case 'CONFIGURED_NEVER_CLONED':
      return { kind: 'configuredNeverCloned' }
    case 'CLONING':
      return { kind: 'cloning', slow: elapsedMs >= CLONE_SLOW_MS, long: elapsedMs >= CLONE_LONG_MS, elapsedMs }
    case 'CLONE_FAILED':
      // A failure whose reason the server could not determine stays honestly UNKNOWN — never guessed into
      // AUTH or URL, which would send the operator to fix the wrong thing.
      return { kind: 'failed', reason: config.cloneFailReason ?? 'UNKNOWN' }
    case 'CLONED_OK':
      return { kind: 'ok' }
    default:
      // An unrecognised status from a newer server is UNKNOWN, not OK: forward-compatibility must fail closed.
      return { kind: 'unknown' }
  }
}

/** Terminal = the server reached a verdict. Only these stop the poll; thinning is not stopping. */
export function isCloneTerminal(view: CloneView): boolean {
  return view.kind === 'ok' || view.kind === 'failed'
}

/** Poll interval for the current view — thinned once long-running, never zero, never abandoned. */
export function clonePollMs(view: CloneView): number | null {
  if (isCloneTerminal(view)) return null // a verdict exists; nothing left to watch
  if (view.kind === 'cloning' && view.long) return CLONE_POLL_THINNED_MS
  return CLONE_POLL_MS
}

/** `CLONED_OK` is the only state that completes the §3.2 repo step. Everything else is "not done yet". */
export function isCloneDone(view: CloneView): boolean {
  return view.kind === 'ok'
}
