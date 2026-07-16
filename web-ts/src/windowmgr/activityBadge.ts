// CYP-641 (P1, Epic CYP-640) — the pure policy for a per-agent-window LIVE ACTIVITY badge, driven by the two
// feeds this story mounts (`/ws/busy-state`, `/ws/token-usage`) plus the already-mounted run-state (`/ws/lifecycle`).
// Parity with the CMP title-bar indicator (`busyFor`/`contextTokensFor`, CYP-316/CYP-324) + the honest-ERROR
// attention marker (CYP-55 A1). Framework-free + unit-tested so the honesty rules are proven, not asserted in a
// component.
//
// **Fail-closed (unknown ≠ activity):** an ABSENT key is never "busy" and never a token count — only an explicit
// `busy=true` lights the marker, only a numeric `contextTokens` shows a number. When nothing is known, the derive
// returns `null` and the caller renders NO badge (absence is the honest empty state, mirroring the CMP badge).
//
// **Attention dominates, and suppresses "busy":** an agent in `ERROR` is not healthily "working" — showing a busy
// marker next to an error would be a mixed signal, so `busy` is cleared under attention. The context-token count is
// orthogonal (a passive size hint) and is shown whenever numeric, error or not.
//
// NOTE — scope boundary: this is the feed-driven ACTIVITY surface only. The CMP tri-variant `WindowBadge`
// Count (comm-unread, needs last-focused tracking) and Severity (event-tail max, operator-gated) are a SEPARATE
// surface not fed by these two channels — deliberately out of CYP-641, a candidate fast-follow.
import type { AgentRunState } from '../state/hubReducers'

export interface WindowActivity {
  /** honest agent ERROR (run-state === 'ERROR') — a warning marker, never a faked "waiting for input". */
  attention: boolean
  /** the agent is actively working now (explicit `busy=true`); suppressed under [attention] to avoid a mixed signal. */
  busy: boolean
  /** live context-token count, shown only when numeric; `null` = unknown/absent → no number. */
  contextTokens: number | null
}

export interface ActivityInput {
  runState: AgentRunState | undefined
  /** the folded `/ws/busy-state` value; caller passes `busyByAgent.get(id) ?? false` (absent → false). */
  busy: boolean
  /** the folded `/ws/token-usage` value; `number` shows, `null`/`undefined`/non-finite → no number. */
  contextTokens: number | null | undefined
}

/**
 * Derive the single activity descriptor for one agent window, or `null` when there is nothing to show (fail-closed).
 * Pure: same inputs → same output, no side effects.
 */
export function deriveWindowActivity(input: ActivityInput): WindowActivity | null {
  const attention = input.runState === 'ERROR'
  // A number only; guard against NaN/Infinity and the absent/null cases. Negative is not a real token count → drop.
  const raw = input.contextTokens
  const contextTokens = typeof raw === 'number' && Number.isFinite(raw) && raw >= 0 ? raw : null
  const busy = input.busy === true && !attention
  if (!attention && !busy && contextTokens === null) return null // nothing known → no badge (honest empty state)
  return { attention, busy, contextTokens }
}

/**
 * Layout-stable compact formatting for the context-token count (the title bar must not grow with magnitude):
 * `< 1000` → the number; `< 1_000_000` → `N.Nk` (one decimal, trailing `.0` trimmed); else `N.Nm`. Deterministic,
 * locale-free (a bare ASCII number so it reads identically everywhere and stays test-stable).
 */
export function formatContextTokens(n: number): string {
  if (n < 1000) return String(n)
  if (n < 1_000_000) return trimZero(n / 1000) + 'k'
  return trimZero(n / 1_000_000) + 'm'
}

function trimZero(x: number): string {
  const s = x.toFixed(1)
  return s.endsWith('.0') ? s.slice(0, -2) : s
}
