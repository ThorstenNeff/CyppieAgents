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
  /** CYP-515 — the URL carries a Kratos flow return (`?flow=`). When None, DON'T auto re-redirect (that loops and
   *  self-DoSes the rate limit); show a neutral continue-to-login state instead. Does NOT relax the gate — state is
   *  still None, children never render (a crafted `?flow=` reaches no app content without a session). */
  flowReturnPresent?: boolean
  children: (operator: boolean) => ReactNode
}

export function AuthGate({ fetchAuthMe, redirectToLogin, redirectToLogout, breakGlass = false, flowReturnPresent = false, children }: AuthGateProps) {
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
  // CYP-515: but NOT when a `?flow=` return is present — re-initiating the flow-init on a flow return loops endlessly
  // and self-DoSes the rate limit (429). That case fails closed to a neutral, manual continue-to-login screen instead.
  useEffect(() => {
    if (state.kind === 'none' && !flowReturnPresent) redirectToLogin()
  }, [state.kind, redirectToLogin, flowReturnPresent])

  if (state.kind === 'resolving') {
    // NOTHING of the app renders yet — no window, no operator control (anti-flash, tooth 7).
    return (
      <div className="auth-screen" role="status" data-testid="auth.loading">
        {AUTH_TEXT.loading}
      </div>
    )
  }

  if (state.kind === 'none') {
    // CYP-515: Kratos handed the login flow back to the app (misconfigured ui_url → SPA). DON'T re-init the flow
    // (loops, self-DoSes); render a NEUTRAL continue-to-login state — never a credential form (§1), never blank.
    // App content stays gated: state is still None, so children never render regardless of the `?flow=` param.
    if (flowReturnPresent) {
      return (
        <div className="auth-screen" role="status" data-testid="auth.flowStranded">
          <p>{AUTH_TEXT.flowStrandedBody}</p>
          <button type="button" className="auth-retry-signin" data-testid="auth.retrySignin" onClick={redirectToLogin}>
            {AUTH_TEXT.retrySignin}
          </button>
        </div>
      )
    }
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
