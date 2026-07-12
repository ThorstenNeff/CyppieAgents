// CYP-470 (P2-i) — the redirect-only auth session-gate. Wraps the whole app: it resolves whoami (GET /api/auth/me)
// BEFORE rendering any app content (resolve-then-render — no unauth/operator flash, tooth 7), then:
//   - None (unauth / whoami error, fail-closed) → a redirect status screen + window redirect to the Kratos login flow;
//   - Unverified (authenticated, verified=false) → a verify-gate, NO app access (tooth 5);
//   - Active → a content-free session indicator (role + logout) + the app, with operator = role==='OPERATOR'.
// web-ts NEVER renders a credential surface (login/register/reset/verify live Kratos-hosted, §1). Logout = the Kratos
// logout flow (server-authoritative, §4), never a client cookie-clear. A global 401 (net/rest setOnUnauthorized) is a
// re-auth redirect. break-glass (injected operator token) bypasses the whoami gate — that path authenticates by Bearer.
import { useEffect, useState, type ReactNode } from 'react'
import type { AuthMe } from '../types/generated/contract'
import { resolveAuthState, signedInAs, AUTH_TEXT, type AuthState } from './authModel'

export interface AuthGateProps {
  fetchAuthMe: () => Promise<AuthMe>
  redirectToLogin: () => void
  redirectToLogout: () => void
  /** injected operator token (isOperatorServe) → skip the whoami gate; the Bearer authenticates every request. */
  breakGlass?: boolean
  children: (operator: boolean) => ReactNode
}

export function AuthGate({ fetchAuthMe, redirectToLogin, redirectToLogout, breakGlass = false, children }: AuthGateProps) {
  const [state, setState] = useState<AuthState>(breakGlass ? { kind: 'active', operator: true } : { kind: 'resolving' })

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

  // None → bounce to the Kratos login flow (the screen below shows the honest "redirecting" status meanwhile).
  useEffect(() => {
    if (state.kind === 'none') redirectToLogin()
  }, [state.kind, redirectToLogin])

  if (state.kind === 'resolving') {
    // NOTHING of the app renders yet — no window, no operator control (anti-flash, tooth 7).
    return (
      <div className="auth-screen" role="status" data-testid="auth.loading">
        {AUTH_TEXT.loading}
      </div>
    )
  }

  if (state.kind === 'none') {
    return (
      <div className="auth-screen" role="status" data-testid="auth.redirect">
        {AUTH_TEXT.redirectingSignin}
      </div>
    )
  }

  if (state.kind === 'unverified') {
    // an honest state screen — NOT a silent redirect loop; NO app access (guarded routes still 401).
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
