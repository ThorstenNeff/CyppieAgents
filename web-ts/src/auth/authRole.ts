// CYP-751 — the platform authZ role values, SINGLE-SOURCED on the client.
//
// whoami's `AuthMe.role` is a bare `string` in the contract by DELIBERATE :core design (AuthMe.kt: "a String, not
// the server-only `AuthRole`, to keep this a thin cross-module contract"). So TypeScript cannot catch a value drift:
// if the server's emitted role string ever diverged from the literal the client compares against, every operator
// would silently fail-close to MEMBER (authModel) and the roster would mislabel (rosterModel) — a green suite staying
// green while the string moved. That is the exact HIGH-severity hole CYP-751 closes.
//
// Two defences: (1) centralise the literal HERE so it lives in one place instead of duplicated across authModel +
// rosterModel; (2) `authRoleParity.test.ts` pins THIS constant to the authoritative :core AuthMe.kt declaration
// (readFileSync, RED on drift) — the check we cannot get from a bare-string contract at compile time.
export const AUTH_ROLE = {
  OPERATOR: 'OPERATOR',
  MEMBER: 'MEMBER',
} as const

export type AuthRole = (typeof AUTH_ROLE)[keyof typeof AUTH_ROLE]
