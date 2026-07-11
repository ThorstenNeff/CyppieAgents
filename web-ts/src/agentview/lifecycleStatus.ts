// CYP-431 (P2-a) — the pure lifecycle-header logic: status-dot spec (faithful port of :app:shared CYP-396
// statusDotSpec) + labels + the dot role→token map. Colour is never the sole signal (WCAG 1.4.1): the text label
// carries the meaning, the dot only reinforces it. UNKNOWN (no lifecycle event yet) is a RING (a different AXIS
// from STOPPED's filled disc), role `outline` (≥3:1, not the near-invisible outlineVariant). Pure & tested.
import { RestError } from '../net/rest'
import type { AgentRunState, LifecycleAction } from '../state/hubReducers'

/** RUNNING/STOPPED/ERROR from the feed, plus UNKNOWN before the first event. */
export type LifecycleState = AgentRunState | 'UNKNOWN'

export type StatusDotShape = 'fill' | 'ring'
export type StatusDotRole = 'primary' | 'outline' | 'error' | 'neutral'

export interface StatusDotSpec {
  shape: StatusDotShape
  role: StatusDotRole
}

/** Form + colour-role as a pure decision (testable without a pixel compare) — port of CYP-396. A pending request
 *  shows the NEUTRAL filled dot (in-progress, distinct from RUNNING=primary), never a resolved colour. */
export function statusDotSpec(state: LifecycleState, pending: boolean): StatusDotSpec {
  if (pending) return { shape: 'fill', role: 'neutral' }
  switch (state) {
    case 'RUNNING':
      return { shape: 'fill', role: 'primary' }
    case 'STOPPED':
      return { shape: 'fill', role: 'outline' }
    case 'ERROR':
      return { shape: 'fill', role: 'error' }
    case 'UNKNOWN':
      return { shape: 'ring', role: 'outline' } // the CYP-396 fix: a ring, not a pale disc
  }
}

/** The maritime token a dot role paints with (neutral = onSurfaceVariant, a0: distinct from RUNNING=primary). */
export function dotRoleVar(role: StatusDotRole): string {
  switch (role) {
    case 'primary':
      return 'var(--md-sys-color-primary)'
    case 'outline':
      return 'var(--md-sys-color-outline)'
    case 'error':
      return 'var(--md-sys-color-error)'
    case 'neutral':
      return 'var(--md-sys-color-on-surface-variant)'
  }
}

/** CYP-445 (§5 enablement matrix): which control is enabled for a given state. Operator-gated (server-authoritative
 *  mirror) and never while a request is in flight. Start only when NOT running; Stop only when running; Restart
 *  whenever an operator can act. The §8.4 tooth: Start must be DISABLED while RUNNING (the old `!operator||pending`
 *  left it clickable). */
export function lifecycleControlEnabled(
  action: LifecycleAction,
  state: LifecycleState,
  operator: boolean,
  pending: boolean,
): boolean {
  if (!operator || pending) return false
  switch (action) {
    case 'start':
      return state !== 'RUNNING'
    case 'stop':
      return state === 'RUNNING'
    case 'restart':
      return true
  }
}

/** CYP-445 (§6): a user-facing reason for a rejected lifecycle action — 409 (conflict/transition) and 503
 *  (unavailable) get distinct copy; anything else is a generic retryable failure. Kept SEPARATE from the CYP-421
 *  ERROR-state reason (that's the agent's own error, not the action's rejection). */
export function lifecycleRejectMessage(err: unknown): string {
  if (err instanceof RestError && err.status === 409) return 'Konflikt — der Agent ist gerade in einem Übergang.'
  if (err instanceof RestError && err.status === 503) return 'Dienst nicht verfügbar — bitte später erneut versuchen.'
  return 'Aktion fehlgeschlagen — bitte erneut versuchen.'
}

/** The honest status label: a transient in-flight label while pending (never a resolved state before the server
 *  confirms it — CYP-431 non-optimistic), else the resolved run-state. */
export function lifecycleLabel(state: LifecycleState, pending: LifecycleAction | undefined): string {
  if (pending === 'start') return 'Startet…'
  if (pending === 'restart') return 'Neustart…'
  if (pending === 'stop') return 'Stoppt…'
  switch (state) {
    case 'RUNNING':
      return 'Aktiv'
    case 'STOPPED':
      return 'Gestoppt'
    case 'ERROR':
      return 'Fehler'
    case 'UNKNOWN':
      return 'Unbekannt'
  }
}
