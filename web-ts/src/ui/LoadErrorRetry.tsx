// CYP-288 — the shared honest load-error + retry surface. A failed INITIAL data load must render THIS (an error line
// + a retry affordance), NEVER the panel's empty state — a failed load ≠ "no data" (systemic honesty class: never
// present a failed state as an empty success). Canonicalizes the shape already shipped inline three times (EventBrowse
// `firstPageError`, ChannelSharePanel `loadError`, AgentSettingsPanel `loadError`): a `role="alert"` line + an
// "Erneut versuchen" button. Presentational + prop-driven — the PANEL decides WHEN to show it, with the honest
// precedence `data ? data : loadError ? <LoadErrorRetry/> : empty` (so live/WS data or a successful retry naturally
// hides it). Testid convention: `<panel>.loadError` on the wrapper + `<panel>.loadError.retry` on the button.
export const LOAD_ERROR_DEFAULT_MESSAGE = 'Laden fehlgeschlagen.'
export const LOAD_ERROR_RETRY_LABEL = 'Erneut versuchen'

export interface LoadErrorRetryProps {
  /** Wrapper testid; the retry button is `${testId}.retry`. Use the `<panel>.loadError` convention. */
  testId: string
  onRetry: () => void
  message?: string
}

export function LoadErrorRetry({ testId, onRetry, message = LOAD_ERROR_DEFAULT_MESSAGE }: LoadErrorRetryProps) {
  return (
    <div className="load-error" role="alert" data-testid={testId}>
      <span className="load-error-msg">{message}</span>
      <button type="button" className="load-error-retry" data-testid={`${testId}.retry`} onClick={onRetry}>
        {LOAD_ERROR_RETRY_LABEL}
      </button>
    </div>
  )
}
