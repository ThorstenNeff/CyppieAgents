// CYP-407 (W9) — the composer disclosure state (CYP-53 §1, kept word-for-word separate): three states, three
// texts, NEVER merged. `readonly` = proactive read-only (no canWrite) — not a dead field; `denied` = an ACL-
// rejected send attempt; `failed` = a generic send error. Pure so the distinction can't be flattened in wiring.
export type ComposerDisclosure = 'ok' | 'readonly' | 'denied' | 'failed'

export function composerDisclosure(canWrite: boolean | null, sendError: string | null): ComposerDisclosure {
  if (canWrite === false) return 'readonly' // proactive read-only — no send affordance
  if (sendError === 'comm_send_denied') return 'denied' // ACL rejected the attempt
  if (sendError !== null) return 'failed' // generic failure
  return 'ok'
}
