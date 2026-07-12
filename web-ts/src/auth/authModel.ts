// CYP-470 (P2-i) — the auth session-gate honesty core (pure). redirect-only (ratified): web-ts NEVER renders a
// credential surface; the four states come from GET /api/auth/me → AuthMe{authenticated, role?, verified} (content-free).
// whoami is the truth for operator/member (not the injected token alone); fail-closed everywhere — a whoami error or
// an unclear state resolves to None (→ login redirect), never optimistically "authenticated/operator".
import type { AuthMe } from '../types/generated/contract'

export type AuthState =
  | { kind: 'resolving' } // whoami in flight — render NOTHING of the app yet (anti-flash, tooth 7)
  | { kind: 'none' } // unauth / whoami error → redirect to the Kratos login flow
  | { kind: 'unverified' } // authenticated but verified=false → verify-gate, NO app access (tooth 5)
  | { kind: 'active'; operator: boolean } // verified session; operator = role==='OPERATOR' (fail-closed member)

/** Resolve the session state. `me === null` (whoami network error) is fail-closed to None → login redirect. */
export function resolveAuthState(me: AuthMe | null): AuthState {
  if (me === null || !me.authenticated) return { kind: 'none' }
  if (!me.verified) return { kind: 'unverified' } // verified=false ≠ access
  return { kind: 'active', operator: me.role === 'OPERATOR' } // whoami drives operator; anything but OPERATOR → member
}

export const AUTH_TEXT = {
  loading: 'Wird geladen…', // auth_loading
  redirectingSignin: 'Weiterleitung zur Anmeldung…', // auth_redirecting_signin
  sessionExpired: 'Sitzung abgelaufen – neue Anmeldung…', // auth_session_expired
  // CYP-515: Kratos returned the login flow to the app (`?flow=`) but there is no session — a misconfigured ui_url
  // pointing at the SPA. A NEUTRAL, non-looping "continue to login" (no auto-redirect → no rate-limit loop; no
  // credential field → §1 redirect-only boundary holds). Not a blank/dead-end: it offers the manual login handoff.
  flowStrandedBody: 'Bitte melde dich an, um fortzufahren.', // auth_flow_stranded_body
  retrySignin: 'Zur Anmeldung', // auth_retry_signin
  verifyTitle: 'E-Mail bestätigen', // auth_verify_pending_title
  // AuthMe is content-free (no email field) → generic body, no interpolation (the email would be a server-work add,
  // deferred per spec §6). Honest: it names the required action without rendering an identity/secret.
  verifyBody: 'Bitte bestätige deine E-Mail, um fortzufahren.', // auth_verify_pending_body (generic; content-free)
  logout: 'Abmelden', // auth_logout
  roleOperator: 'Operator', // auth_role_operator
  roleMember: 'Member', // auth_role_member
} as const

/** The content-free session indicator text (role only — text + label, never colour alone; no id/email/secret, §6). */
export function signedInAs(operator: boolean): string {
  return `Angemeldet als ${operator ? AUTH_TEXT.roleOperator : AUTH_TEXT.roleMember}` // auth_signed_in_as
}
