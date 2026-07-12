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

/** CYP-515 — true when the URL carries a Kratos self-service flow return (`?flow=<id>`). In the ratified redirect-only
 *  design (§1, Kratos-hosted login UI) the SPA is NEVER the login `ui_url`, so it should never receive this. If a
 *  misconfigured `ui_url` points back at the app, re-initiating the flow-init on this return would loop endlessly and
 *  self-DoS the rate limit (429). The AuthGate reads this to FAIL CLOSED (no auto re-redirect) instead of hammering. */
export function hasLoginFlowReturn(search: string): boolean {
  const flow = new URLSearchParams(search).get('flow')
  return flow !== null && flow !== '' // present-but-empty is not a real flow return → normal login redirect stands
}
