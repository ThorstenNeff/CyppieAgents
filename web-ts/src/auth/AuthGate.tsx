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
import { useEffect, useState, type ReactNode } from 'react'
import type { AuthMe } from '../types/generated/contract'
import { setOnUnauthorized } from '../net/rest'
import { resolveAuthState, signedInAs, AUTH_TEXT, type AuthState, type LoginResult } from './authModel'
import { LoginScreen } from './LoginScreen'
import { RemoteSecurityTierBadge } from '../connector/RemoteSecurityTierBadge'
import { gatewayTierFor } from '../connector/gatewayTier'
import { useHubStore } from '../state/hubStore'

/**
 * CYP-733 (spec §2+§3) — the connection-security tier, shown with the session.
 *
 * Mounted HERE and nowhere else on purpose: the session surface is the outermost always-visible chrome (it wraps
 * the whole app and survives every window being closed), so ONE placement satisfies both the connect disclosure
 * (§2) and the session one (§3). Rendering it in two places would print the same honesty sentence twice, and
 * repetition reads as boilerplate — it dilutes the statement it is meant to make.
 *
 * Reads the live-connection signal from the store because this gate sits ABOVE the app that owns it. Tier is
 * UNKNOWN whenever the connection is not up, and can never be NATIVE (see gatewayTier).
 */
function SessionTierBadge() {
  const connection = useHubStore((s) => s.commConnection)
  return (
    <div className="auth-session-tier" data-testid="auth.sessionTier">
      <RemoteSecurityTierBadge tier={gatewayTierFor(connection)} />
    </div>
  )
}

export interface AuthGateProps {
  fetchAuthMe: () => Promise<AuthMe>
  /** Submits the in-app login credentials (loginFlow.createLogin). */
  login: (email: string, password: string) => Promise<LoginResult>
  redirectToLogout: () => void
  /** injected operator token (isOperatorServe) → skip the whoami gate; the Bearer authenticates every request. */
  breakGlass?: boolean
  /** Install seam for the global 401 handler (defaults to net/rest setOnUnauthorized; injectable for tests). */
  onInstallUnauthorized?: (handler: (() => void) | null) => void
  children: (operator: boolean) => ReactNode
}

export function AuthGate({
  fetchAuthMe,
  login,
  redirectToLogout,
  breakGlass = false,
  onInstallUnauthorized = setOnUnauthorized,
  children,
}: AuthGateProps) {
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

  // A protected /api 401 (session expired/revoked mid-session) → back to the in-app LoginScreen. IN-APP re-auth (no
  // window redirect), so this can never re-introduce the CYP-515 redirect loop. Login-submit 401s never reach here —
  // loginFlow uses a direct fetch that bypasses this hook (spec §2.3④).
  useEffect(() => {
    if (breakGlass) return
    onInstallUnauthorized(() => setState({ kind: 'none' }))
    return () => onInstallUnauthorized(null)
  }, [breakGlass, onInstallUnauthorized])

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
      <SessionTierBadge />
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
