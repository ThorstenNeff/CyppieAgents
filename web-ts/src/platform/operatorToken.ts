/**
 * CYP-398 (W0) — the operator-token seam. The deploy/proxy injects `CYPPIE_OPERATOR_TOKEN` as a global before
 * the bundle on the OPERATOR serve; the public/MEMBER serve injects nothing (Spec 14 §6). Read it here, never
 * hardcode it. Absent -> `null` -> fail-closed MEMBER posture. Later slices gate the operator surfaces on this.
 */
export function operatorToken(): string | null {
  return (globalThis as { CYPPIE_OPERATOR_TOKEN?: string }).CYPPIE_OPERATOR_TOKEN ?? null
}

/** True on the operator serve (token present), false on the public/MEMBER serve. */
export function isOperatorServe(): boolean {
  return operatorToken() !== null
}
