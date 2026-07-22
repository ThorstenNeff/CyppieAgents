// CYP-515 (a) — the in-app auth session-gate. REVERSES CYP-470's redirect-only posture (ratified (a) decision, PO1):
// web-ts now renders the credential surface itself (hardened, LoginScreen), matching the live WASM API-flow. Wraps the
// whole app: it resolves whoami (GET /api/auth/me) BEFORE rendering any app content (resolve-then-render — no
// unauth/operator flash), then:
//   - None (unauth / whoami error, fail-closed) → the in-app LoginScreen (NOT a redirect → the CYP-515 flow-return
//     loop is impossible by construction; the ?flow= guard is deferred to the OIDC-P2 screen per spec §5);
//   - Unverified (authenticated, verified=false) → a verify-gate, NO app access;
//   - Active → a content-free session indicator (role + logout) + the app, with operator = role==='OPERATOR'.
// A global /api 401 (net/rest setOnUnauthorized) flips the gate back to None → the in-app LoginScreen (re-auth stays,
// but IN-APP — no window redirect, so a session-expiry can't re-introduce the redirect loop). break-glass (injected
// operator token) bypasses the whoami gate — that path authenticates by Bearer.
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import type { AuthMe } from '../types/generated/contract'
import { setOnUnauthorized } from '../net/rest'
import type { HubId } from '../net/hubRegistry'
import { resolveAuthState, signedInAs, AUTH_TEXT, type AuthState, type LoginResult } from './authModel'
import { LoginScreen } from './LoginScreen'

export interface AuthGateProps {
  /** CYP-800 (N4.b): the active hub whose 401 this gate re-auths. The default installer binds THIS hubId so a 401
   *  from another hub never flips this gate. */
  activeHubId: HubId
  fetchAuthMe: () => Promise<AuthMe>
  /** Submits the in-app login credentials (loginFlow.createLogin). */
  login: (email: string, password: string) => Promise<LoginResult>
  redirectToLogout: () => void
  /** injected operator token (isOperatorServe) → skip the whoami gate; the Bearer authenticates every request. */
  breakGlass?: boolean
  /** Install seam for the hub 401 handler (defaults to a net/rest setOnUnauthorized bound to activeHubId; injectable
   *  for tests). Called with a handler to install and null to clear — the hubId is already bound. */
  onInstallUnauthorized?: (handler: (() => void) | null) => void
  children: (operator: boolean) => ReactNode
}

export function AuthGate({
  activeHubId,
  fetchAuthMe,
  login,
  redirectToLogout,
  breakGlass = false,
  onInstallUnauthorized,
  children,
}: AuthGateProps) {
  const [state, setState] = useState<AuthState>(breakGlass ? { kind: 'active', operator: true } : { kind: 'resolving' })
  // CYP-800: the installer is bound to the active hub (setOnUnauthorized is now per-hubId). Memoised on
  // [onInstallUnauthorized, activeHubId] so the install/clear effect below does not churn every render.
  const installUnauthorized = useMemo(
    () => onInstallUnauthorized ?? ((handler: (() => void) | null) => setOnUnauthorized(activeHubId, handler)),
    [onInstallUnauthorized, activeHubId],
  )

  // resolve-then-render: fetch whoami first; only then decide what to mount. Fail-closed to None on any error.
  useEffect(() => {
    if (breakGlass) return
    let live = true
    fetchAuthMe()
      .then((me) => live && setState(resolveAuthState(me)))
      .catch(() => live && setState({ kind: 'none' }))
    return () => {
      live = false
    }
  }, [breakGlass, fetchAuthMe])

  // A protected /api 401 (session expired/revoked mid-session) → back to the in-app LoginScreen. IN-APP re-auth (no
  // window redirect), so this can never re-introduce the CYP-515 redirect loop. Login-submit 401s never reach here —
  // loginFlow uses a direct fetch that bypasses this hook (spec §2.3④).
  useEffect(() => {
    if (breakGlass) return
    installUnauthorized(() => setState({ kind: 'none' }))
    return () => installUnauthorized(null)
  }, [breakGlass, installUnauthorized])

  if (state.kind === 'resolving') {
    // NOTHING of the app renders yet — no window, no operator control, no login flash (anti-flash).
    return (
      <div className="auth-screen" role="status" data-testid="auth.loading">
        {AUTH_TEXT.loading}
      </div>
    )
  }

  if (state.kind === 'none') {
    // In-app login (hardened credential surface). Success mounts the app at the whoami-resolved tier; unverified goes
    // to the verify-gate. No redirect anywhere → no loop.
    return (
      <LoginScreen
        login={login}
        onVerified={(operator) => setState({ kind: 'active', operator })}
        onUnverified={() => setState({ kind: 'unverified' })}
      />
    )
  }

  if (state.kind === 'unverified') {
    // an honest state screen — NO app access (guarded routes still 401).
    return (
      <div className="auth-screen auth-verify" role="region" aria-label={AUTH_TEXT.verifyTitle} data-testid="auth.verifyGate">
        <h2>{AUTH_TEXT.verifyTitle}</h2>
        <p>{AUTH_TEXT.verifyBody}</p>
      </div>
    )
  }

  // active: content-free session indicator (role text+label, never colour alone; no id/email/secret) + the app.
  return (
    <>
      <div className="auth-session-status" role="status" data-testid="auth.sessionStatus">
        <span data-testid="auth.role">{signedInAs(state.operator)}</span>
        <button type="button" className="auth-logout" data-testid="auth.logout" onClick={redirectToLogout}>
          {AUTH_TEXT.logout}
        </button>
      </div>
      {children(state.operator)}
    </>
  )
}
