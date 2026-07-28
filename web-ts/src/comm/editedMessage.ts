// CYP-906 (Edit / NR E1) — the edit envelope helpers. The server stamps an edit time on the DeliveredMessage ENVELOPE
// (`editedAt: number | null`, CYP-905; additive, NOT on Message1 — the CYP-744 envelope-share tax keeps the /ws/hub BYOA
// wire untouched). The field is now real in the generated contract + zod schema (it survives validation on REST + WS).
import type { DeliveredMessage } from '../types/generated/contract'

/** The server-stamped edit time (epoch ms) for this envelope, or null when the message has never been edited. */
export function editedAt(d: DeliveredMessage): number | null {
  return d.editedAt ?? null
}

/**
 * Newer-wins for the upsert-by-id: an incoming envelope with the SAME message id supersedes the existing one ONLY if it
 * is a strictly newer edit — a newer `editedAt`, or the first edit of a not-yet-edited message. A stale reconnect-replay
 * of the ORIGINAL (no/older editedAt) must NEVER clobber a live edit (the load-bearing invariant); an identical replay
 * (same editedAt, incl. both-null originals) is a no-op.
 */
export function isNewerEdit(incoming: DeliveredMessage, existing: DeliveredMessage): boolean {
  const inc = editedAt(incoming)
  const exi = editedAt(existing)
  return inc !== null && (exi === null || inc > exi)
}
