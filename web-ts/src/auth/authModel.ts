// CYP-470 (P2-i) — the auth session-gate honesty core (pure). redirect-only (ratified): web-ts NEVER renders a
// credential surface; the four states come from GET /api/auth/me → AuthMe{authenticated, role?, verified} (content-free).
// whoami is the truth for operator/member (not the injected token alone); fail-closed everywhere — a whoami error or
// an unclear state resolves to None (→ login redirect), never optimistically "authenticated/operator".
import type { AuthMe } from '../types/generated/contract'

export type AuthState =
  | { kind: 'resolving' } // whoami in flight — render NOTHING of the app yet (anti-flash, tooth 7)
  | { kind: 'none' } // unauth / whoami error → the in-app login screen (CYP-515 (a): NO redirect; loop dies by construction)
  | { kind: 'unverified' } // authenticated but verified=false → verify-gate, NO app access (tooth 5)
  | { kind: 'active'; operator: boolean } // verified session; operator = role==='OPERATOR' (fail-closed member)

/** Resolve the session state. `me === null` (whoami network error) is fail-closed to None → in-app login. */
export function resolveAuthState(me: AuthMe | null): AuthState {
  if (me === null || !me.authenticated) return { kind: 'none' }
  if (!me.verified) return { kind: 'unverified' } // verified=false ≠ access
  return { kind: 'active', operator: me.role === 'OPERATOR' } // whoami drives operator; anything but OPERATOR → member
}

// CYP-515 (a) — the in-app login-core outcome (the redirect-only posture of CYP-470 is reversed here per the ratified
// (a) decision; the credential surface comes back into web-ts, hardened). Rejected is ALWAYS generic (no enumeration).
export type LoginResult =
  | { kind: 'verified'; operator: boolean } // session established + verified → mount the app at this tier
  | { kind: 'unverified' } // session established but email not verified → the verify-gate (no app access)
  | { kind: 'rejected' } // ANY bad-credential / flow / transport failure → ONE generic outcome (enumeration-safe, fail-closed)
  | { kind: 'rateLimited'; retryAfter: string | null } // 429 — honest throttle, never a fake success

// The login submit phase (one axis, no spinner). `error` is the ONE generic message; `rateLimited` is amber, not error.
export type LoginPhase =
  | { kind: 'idle' }
  | { kind: 'submitting' } // fields + button disabled, label → submitting; NO spinner
  | { kind: 'error' } // generic auth error (role=alert); manual retry
  | { kind: 'rateLimited'; retryAfter: string | null } // amber (role=status), submit disabled until it clears

/** Map a resolved whoami into the post-login outcome (server is the source of truth; None post-success → fail-closed). */
export function loginOutcomeFromAuth(me: AuthMe | null): LoginResult {
  const s = resolveAuthState(me)
  if (s.kind === 'active') return { kind: 'verified', operator: s.operator }
  if (s.kind === 'unverified') return { kind: 'unverified' }
  return { kind: 'rejected' } // resolving/none post-2xx-login is unexpected → generic fail-closed, never optimistic
}

export const AUTH_TEXT = {
  loading: 'Wird geladen…', // auth_loading
  redirectingSignin: 'Weiterleitung zur Anmeldung…', // auth_redirecting_signin
  sessionExpired: 'Sitzung abgelaufen – neue Anmeldung…', // auth_session_expired
  verifyTitle: 'E-Mail bestätigen', // auth_verify_pending_title
  // AuthMe is content-free (no email field) → generic body, no interpolation (the email would be a server-work add,
  // deferred per spec §6). Honest: it names the required action without rendering an identity/secret.
  verifyBody: 'Bitte bestätige deine E-Mail, um fortzufahren.', // auth_verify_pending_body (generic; content-free)
  logout: 'Abmelden', // auth_logout
  roleOperator: 'Operator', // auth_role_operator
  roleMember: 'Member', // auth_role_member
  // CYP-515 (a) login-core (mapped from the KMP auth_* keys; 0 new tags). The error is ONE generic string for EVERY
  // auth failure (enumeration-safety, §2.3②); rate-limit is amber and honest, never an error tone (§2.3③).
  loginTitle: 'Anmelden', // auth_login_title
  emailLabel: 'E-Mail', // auth_email_label
  passwordLabel: 'Passwort', // auth_password_label
  passwordShow: 'Anzeigen', // auth_password_show (text label, never an emoji — CYP-99/CYP-54)
  passwordHide: 'Verbergen', // auth_password_hide
  submitLogin: 'Anmelden', // auth_submit_login
  submitting: 'Wird gesendet…', // auth_submitting (label only; NO spinner)
  loginErrorGeneric: 'Anmeldung fehlgeschlagen. Bitte prüfe deine Eingaben.', // auth_login_error_generic (never enumerating)
  rateLimited: 'Zu viele Versuche. Bitte kurz warten.', // auth_rate_limited
} as const

/** Honest 429 text (§2.3③): with a server retry hint → the "…in <x>…" variant, else the plain one. Never a fake success. */
export function rateLimitedText(retryAfter: string | null): string {
  return retryAfter !== null && retryAfter !== ''
    ? `Zu viele Versuche. Bitte in ${retryAfter} erneut versuchen.` // auth_rate_limited_wait
    : AUTH_TEXT.rateLimited
}

/** a11y description for the password reveal toggle — text, mirrors the show/hide label (no emoji). */
export function passwordRevealDesc(revealed: boolean): string {
  return revealed ? AUTH_TEXT.passwordHide : AUTH_TEXT.passwordShow // a11y_auth_password_hide / _show
}

/** The content-free session indicator text (role only — text + label, never colour alone; no id/email/secret, §6). */
export function signedInAs(operator: boolean): string {
  return `Angemeldet als ${operator ? AUTH_TEXT.roleOperator : AUTH_TEXT.roleMember}` // auth_signed_in_as
}
