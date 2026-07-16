// CYP-644 (P4, Epic CYP-640) — the pure handoff / context-lost banner policy for the agent window. Parity with the
// CMP HandoffBanners (AgentWindow.kt): a persistent WARN-amber landmark strip.
//  - INTERACTIVE  → the shell was handed off (holder @heldBy, since HH:MM) — the mediated view has receded.
//  - CONTEXT_LOST → the interactive session returned WITHOUT its prior history.
//  - everything else (MEDIATED / HANDING_OVER / HANDING_BACK / no event) → NO banner (fail-closed; absent == MEDIATED,
//    and the transient HANDING_* states are in-flight, not a settled handoff).
// Framework-free + unit-tested; the honesty rule (banner only on the two settled states) is proven here.
import type { AgentTerminalControlEvent } from '../types/generated/contract'

export type HandoffBanner =
  | { kind: 'handoff'; heldBy: string | null; since: number | null }
  | { kind: 'contextLost' }

/** Derive the banner for one agent from its latest terminal-control event, or `null` when no banner should show. */
export function handoffBanner(control: AgentTerminalControlEvent | undefined | null): HandoffBanner | null {
  switch (control?.state) {
    case 'INTERACTIVE':
      return { kind: 'handoff', heldBy: control.heldBy ?? null, since: control.since ?? null }
    case 'CONTEXT_LOST':
      return { kind: 'contextLost' }
    default:
      return null // MEDIATED / HANDING_OVER / HANDING_BACK / undefined → no banner (fail-closed)
  }
}

/** Format the handoff `since` epoch-ms as local HH:MM, or `"—"` when absent. Kept tiny + here so the component stays
 *  declarative; the exact clock is the viewer's locale/timezone (a user-facing "since when"). */
export function formatHandoffSince(since: number | null | undefined): string {
  if (since == null || !Number.isFinite(since)) return '—'
  // 24-hour HH:MM (hour12:false) — consistent with the German UI and locale-stable (never "10:13 PM").
  return new Date(since).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })
}

/** The `@holder` label, or `"?"` when the holder identity is absent (mirrors CMP's `heldBy?.let{"@$it"} ?: "?"`). */
export function handoffHolderLabel(heldBy: string | null): string {
  return heldBy == null || heldBy === '' ? '?' : `@${heldBy}`
}
