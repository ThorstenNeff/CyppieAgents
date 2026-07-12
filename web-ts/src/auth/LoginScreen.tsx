// CYP-515 (a) — the in-app login-core screen (reverses CYP-470 redirect-only per the ratified (a) decision). Renders
// the credential surface web-ts previously outsourced to Kratos-hosted, HARDENED per spec §2.3:
//   ① password: type=password + autocomplete=current-password; the value lives ONLY in the input `value` (never a
//      data-*/aria/title attribute); credentials POST in the body (loginFlow); the password is CLEARED after every
//      submit so it never lingers in the DOM; server messages are NEVER rendered — only static generic strings, so
//      there is no innerHTML/dangerouslySetInnerHTML path an XSS could read;
//   ② every failure shows the ONE generic error (no enumeration);
//   ③ a 429 shows the honest amber rate-limit (role=status), never an error tone, never an auto-retry;
//   ⑧ no success-green (success just mounts the app), no spinner (label swap only).
import { useState, type FormEvent } from 'react'
import { AUTH_TEXT, rateLimitedText, passwordRevealDesc, type LoginResult, type LoginPhase } from './authModel'

export interface LoginScreenProps {
  /** Submits the credentials (loginFlow.createLogin). Resolves to a generic outcome — never an enumeration cue. */
  login: (email: string, password: string) => Promise<LoginResult>
  /** Verified session → mount the app at this tier. */
  onVerified: (operator: boolean) => void
  /** Authenticated but unverified → the verify-gate (no app access). */
  onUnverified: () => void
}

export function LoginScreen({ login, onVerified, onUnverified }: LoginScreenProps) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [phase, setPhase] = useState<LoginPhase>({ kind: 'idle' })

  const submitting = phase.kind === 'submitting'
  const throttled = phase.kind === 'rateLimited'
  const canSubmit = !submitting && !throttled && email.trim() !== '' && password !== ''

  // A field edit clears a transient error / rate-limit back to idle (manual retry; never an auto-retry, §2.3③).
  const clearTransient = () => setPhase((p) => (p.kind === 'error' || p.kind === 'rateLimited' ? { kind: 'idle' } : p))

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    setPhase({ kind: 'submitting' })
    const result = await login(email.trim(), password)
    setPassword('') // clear-after-submit (§2.3①): the raw credential never lingers in the DOM value
    switch (result.kind) {
      case 'verified':
        onVerified(result.operator)
        return
      case 'unverified':
        onUnverified()
        return
      case 'rateLimited':
        setPhase({ kind: 'rateLimited', retryAfter: result.retryAfter })
        return
      case 'rejected':
        setPhase({ kind: 'error' }) // ONE generic message, never enumerating
        return
    }
  }

  return (
    <div className="auth-screen">
      <form className="auth-login-form" data-testid="auth.login.form" onSubmit={onSubmit} noValidate>
        <h2 className="auth-title">{AUTH_TEXT.loginTitle}</h2>

        <label className="auth-field">
          <span>{AUTH_TEXT.emailLabel}</span>
          <input
            type="email"
            name="identifier"
            autoComplete="username"
            inputMode="email"
            data-testid="auth.login.email"
            value={email}
            disabled={submitting}
            aria-label={AUTH_TEXT.emailLabel}
            onChange={(e) => {
              setEmail(e.target.value)
              clearTransient()
            }}
          />
        </label>

        <label className="auth-field">
          <span>{AUTH_TEXT.passwordLabel}</span>
          <div className="auth-password-row">
            <input
              type={reveal ? 'text' : 'password'}
              name="password"
              autoComplete="current-password"
              data-testid="auth.login.password"
              value={password}
              disabled={submitting}
              aria-label={AUTH_TEXT.passwordLabel}
              onChange={(e) => {
                setPassword(e.target.value)
                clearTransient()
              }}
            />
            <button
              type="button"
              className="auth-password-reveal"
              data-testid="auth.login.passwordReveal"
              disabled={submitting}
              aria-pressed={reveal}
              aria-label={passwordRevealDesc(reveal)}
              onClick={() => setReveal((r) => !r)}
            >
              {reveal ? AUTH_TEXT.passwordHide : AUTH_TEXT.passwordShow}
            </button>
          </div>
        </label>

        <button type="submit" className="auth-login-submit" data-testid="auth.login.submit" disabled={!canSubmit}>
          {submitting ? AUTH_TEXT.submitting : AUTH_TEXT.submitLogin}
        </button>

        {phase.kind === 'error' && (
          <p className="auth-login-error" role="alert" aria-live="assertive" data-testid="auth.login.error">
            {AUTH_TEXT.loginErrorGeneric}
          </p>
        )}
        {phase.kind === 'rateLimited' && (
          <p className="auth-login-rate-limited" role="status" aria-live="polite" data-testid="auth.login.rateLimited">
            {rateLimitedText(phase.retryAfter)}
          </p>
        )}
      </form>
    </div>
  )
}
