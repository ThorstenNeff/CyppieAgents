// CYP-470 — Kratos self-service flow URLs (redirect-only, CYP-176/230/413 config). Same-origin browser-flow defaults;
// the deploy can override per origin via globals (like the API base / operator token). web-ts only REDIRECTS to these —
// it never renders the credential surface itself (§1).
interface AuthGlobals {
  CYPPIE_LOGIN_URL?: string
  CYPPIE_LOGOUT_URL?: string
}
const g = (): AuthGlobals => globalThis as AuthGlobals

/** The Kratos self-service LOGIN browser flow (redirect target for None / 401). */
export function loginUrl(): string {
  const injected = g().CYPPIE_LOGIN_URL
  return injected !== undefined && injected !== '' ? injected : '/self-service/login/browser'
}

/** The Kratos self-service LOGOUT browser flow (server-authoritative session invalidation, §4). */
export function logoutUrl(): string {
  const injected = g().CYPPIE_LOGOUT_URL
  return injected !== undefined && injected !== '' ? injected : '/self-service/logout/browser'
}
