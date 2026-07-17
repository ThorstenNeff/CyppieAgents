// CYP-646 (P1 fast-follow, Epic CYP-640) — the pure Count + Severity window-badge policy, the two NON-feed channels
// of the CMP tri-variant WindowBadge (deriveWindowBadges, CYP-55). Framework-free + unit-tested.
//  - B1 Count    → on the COMM window: unread comm messages since it was last focused. Focus-gated (hidden while the
//                  comm window is focused) — a focused window has "seen" its activity.
//  - C1 Severity → on the EVENT-LOG window: the tail's max open severity, only at/above WARN. Focus-gated too.
// (A1 Attention — the erroring-agent marker — is already carried by the CYP-641 activity badge, so it is not here.)
import type { Severity } from '../eventlog/eventLog'

export type WindowBadge = { kind: 'count'; count: number } | { kind: 'severity'; severity: Severity }

const RANK: Record<Severity, number> = { debug: 0, info: 1, warn: 2, error: 3 }
export function severityRank(s: Severity): number {
  return RANK[s]
}

/** The max severity across a tail of events, or `null` for an empty tail. */
export function maxTailSeverity(events: readonly { severity: Severity }[]): Severity | null {
  let max: Severity | null = null
  for (const e of events) {
    if (max === null || severityRank(e.severity) > severityRank(max)) max = e.severity
  }
  return max
}

/** The comm window's Count badge: present ⇔ there are unread messages AND the comm window is not focused. Fail-closed:
 *  a focused comm window (or zero unread) → no badge. */
export function commCountBadge(unread: number, commFocused: boolean): WindowBadge | null {
  return !commFocused && unread > 0 ? { kind: 'count', count: unread } : null
}

/** The event window's Severity badge: present ⇔ the tail's max severity is at/above WARN AND the event window is not
 *  focused. Below WARN (debug/info) → no badge (a quiet, non-alarming tail adds no chrome). */
export function eventSeverityBadge(tailMaxSeverity: Severity | null, eventFocused: boolean): WindowBadge | null {
  if (eventFocused || tailMaxSeverity === null) return null
  return severityRank(tailMaxSeverity) >= severityRank('warn') ? { kind: 'severity', severity: tailMaxSeverity } : null
}

/** Layout-stable count text: `> 9` collapses to `"9+"` so the title bar never grows with magnitude (CMP parity). */
export function formatBadgeCount(count: number): string {
  return count > 9 ? '9+' : String(count)
}
