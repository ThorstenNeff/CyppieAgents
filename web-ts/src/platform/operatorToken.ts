/**
 * CYP-398 (W0) — the operator-token seam. The deploy/proxy injects `CYPPIE_OPERATOR_TOKEN` as a global before
 * the bundle on the OPERATOR serve; the public/MEMBER serve injects nothing (Spec 14 §6). Read it here, never
 * hardcode it. Absent -> `null` -> fail-closed MEMBER posture. Later slices gate the operator surfaces on this.
 *
 * CYP-749 (fail-CLOSED) — a BLANK value counts as absent. Previously `?? null` only caught null/undefined, so an
 * empty or whitespace-only `CYPPIE_OPERATOR_TOKEN` (a proxy that declared the variable but left it blank — a
 * misconfiguration, not an operator serve) read as a present token: `isOperatorServe()` went true, unlocking the
 * operator surface AND skipping the whoami gate (AuthGate break-glass). A blank token authenticates nothing, so it
 * must resolve to MEMBER, not operator. Detection trims; the genuine token value is returned unchanged.
 */
export function operatorToken(): string | null {
  const raw = (globalThis as { CYPPIE_OPERATOR_TOKEN?: string }).CYPPIE_OPERATOR_TOKEN
  return typeof raw === 'string' && raw.trim() !== '' ? raw : null
}

/** True on the operator serve (token present), false on the public/MEMBER serve. */
export function isOperatorServe(): boolean {
  return operatorToken() !== null
}
