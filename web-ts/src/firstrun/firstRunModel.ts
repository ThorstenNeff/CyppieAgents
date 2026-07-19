// CYP-735 §3.2 (UIUX2 screen-spec 951823ab) — the guided first-run: gate mode + per-step progress.
//
// THE LOAD-BEARING RULE IS "DONE" = SERVER-VALIDATED, NEVER FORM-SUBMITTED. A step is finished when the SERVER
// confirms the thing exists, not when the user pressed save. Submitting a form proves intent; it does not prove
// the hub can do anything with it.
//
// The repo step makes that distinction visible, and it is the whole reason the two levels exist:
//   • SAVED  = `configured: true` — the URL was ACCEPTED.
//   • DONE   = the repo was actually CLONED (§3.3, seam-blocked on CYP-736).
// An accepted URL is not a clonable repo: it can be a typo, a private repo without credentials, or a dead host.
// Rendering "saved" as "done" would be the false-configured lie — the same family as CYP-735 §3.1's honest-unset,
// one level further out. So until the clone seam exists, the repo step tops out at SAVED and the gate cannot
// legitimately reach TRANSPARENT.
//
// LOADING IS NOT A STEP (spec §6.1): an unresolved config renders a load surface, never "step 1" and never
// "unconfigured" — unknown ≠ unconfigured, the CYP-288/679 class.
import type { SetupStatus } from '../workspace/setupStatus'

/** How the gate presents itself. Derived purely from status — no independent state to drift. */
export type FirstRunGateMode =
  /** Config unresolved (loading or load error) → a load surface. NEVER "step 1". */
  | 'loading'
  /** Something still to do → the guided steps. */
  | 'active'
  /** Both core steps genuinely done → the gate disappears. */
  | 'transparent'

/** Per-step progress. `saved` is an ADVISORY middle state, deliberately distinct from `done`. */
export type StepState = 'open' | 'saved' | 'done'

export interface FirstRunProgress {
  readonly apiKey: StepState
  readonly repo: StepState
  /** Informational only — never gates completion (spec §3/§6.5). */
  readonly team: StepState
}

export interface FirstRunInputs {
  readonly setup: SetupStatus
  /** Server-held API key (`apiKeyView.set`); `null` = not resolved yet. The client never sees the plaintext. */
  readonly apiKeySet: boolean | null
  /** §3.3 clone terminal state. `null` until the CYP-736 seam exists — so repo cannot reach `done` yet. */
  readonly repoCloned: boolean | null
}

/**
 * Progress per step.
 *
 * API key: `done` only on a server-confirmed `set` — the client cannot verify a key it never sees, so the server
 * holding it is the only honest evidence.
 *
 * Repo: `saved` when the config is accepted, `done` only when the clone actually succeeded. With the seam absent
 * (`repoCloned === null`) it stops at `saved` — never promoted, because "we cannot check" is not "it works".
 */
export function firstRunProgress(inputs: FirstRunInputs): FirstRunProgress {
  const configured = inputs.setup.kind === 'configured'
  return {
    apiKey: inputs.apiKeySet === true ? 'done' : 'open',
    repo: inputs.repoCloned === true ? 'done' : configured ? 'saved' : 'open',
    team: 'open', // informational; it has no completion of its own to report
  }
}

/**
 * The gate's mode.
 *
 * `transparent` requires BOTH core steps genuinely done — and `team` is deliberately not consulted: nobody should
 * be blocked from their workspace for not having configured agents yet.
 *
 * An unresolved config is `loading`, never `active`: showing setup steps because we failed to read the config
 * would tell a configured operator to set up their hub.
 */
export function firstRunGateMode(inputs: FirstRunInputs): FirstRunGateMode {
  if (inputs.setup.kind === 'unknown' || inputs.setup.kind === 'error') return 'loading'
  const p = firstRunProgress(inputs)
  return p.apiKey === 'done' && p.repo === 'done' ? 'transparent' : 'active'
}

/** Where a resuming user lands: the first step that is not done (spec §3, resume) — never always step 1. */
export function firstIncompleteStep(progress: FirstRunProgress): 'apikey' | 'repo' | 'team' | null {
  if (progress.apiKey !== 'done') return 'apikey'
  if (progress.repo !== 'done') return 'repo'
  return null // team never blocks completion, so it is never the resume target
}
