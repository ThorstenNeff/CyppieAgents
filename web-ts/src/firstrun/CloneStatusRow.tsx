// CYP-735 §3.3 (UIUX2 screen-spec 12924c37 §1/§5) — the repo step's clone-lifecycle marker.
//
// The honesty lives in what each state is ALLOWED to say. "Saved" says the clone is outstanding, not that the repo
// works. A long-running clone shows its measured DURATION — a fact — rather than a forever-spinner (which asserts
// progress nobody can see) or an alarm (which asserts a failure nobody observed). A failure names its own fix, and
// an undetermined cause stays undetermined rather than being guessed into "auth" and sending the operator to
// rotate a perfectly good token.
import type { CloneView } from './cloneStatusModel'
import {
  CLONE_CLONING,
  CLONE_FAILED_TEXT,
  CLONE_NEVER,
  CLONE_OK,
  CLONE_RETRY,
  CLONE_SLOW_HINT,
  CLONE_UNKNOWN,
  cloneLongHint,
} from './firstRunText'

const TAG = {
  unknown: 'firstrun.repo.cloneUnknown',
  notConfigured: 'firstrun.repo.cloneStatus.notConfigured',
  configuredNeverCloned: 'firstrun.repo.cloneStatus.configuredNeverCloned',
  cloning: 'firstrun.repo.cloneStatus.cloning',
  failed: 'firstrun.repo.cloneStatus.failed',
  ok: 'firstrun.repo.cloneStatus.ok',
} as const

const REASON_TAG = {
  URL_UNREACHABLE: 'firstrun.repo.cloneFailReason.urlUnreachable',
  AUTH: 'firstrun.repo.cloneFailReason.auth',
  UNKNOWN: 'firstrun.repo.cloneFailReason.unknown',
} as const

export function CloneStatusRow({ view, onRetry }: { view: CloneView; onRetry: () => void }) {
  if (view.kind === 'notConfigured') return null // the repo step is simply open; nothing to report yet

  if (view.kind === 'failed') {
    return (
      <div className="clone-status clone-failed" data-testid={TAG.failed} role="status">
        <span data-testid={REASON_TAG[view.reason]}>{CLONE_FAILED_TEXT[view.reason]}</span>
        <button type="button" className="clone-retry" data-testid="firstrun.repo.cloneRetry" onClick={onRetry}>
          {CLONE_RETRY}
        </button>
      </div>
    )
  }

  if (view.kind === 'cloning') {
    return (
      <div className="clone-status clone-cloning" data-testid={TAG.cloning} role="status">
        <span>{CLONE_CLONING}</span>
        {/* advisory only, and deliberately the SAME tone as cloning — a slow clone has not failed and is not stuck.
            The long variant states the measured duration: a fact the operator can act on, unlike a spinner. */}
        {view.long ? (
          <span className="clone-hint" data-testid="firstrun.repo.cloneSlowHint">
            {cloneLongHint(view.elapsedMs)}
          </span>
        ) : view.slow ? (
          <span className="clone-hint" data-testid="firstrun.repo.cloneSlowHint">
            {CLONE_SLOW_HINT}
          </span>
        ) : null}
      </div>
    )
  }

  const text = view.kind === 'ok' ? CLONE_OK : view.kind === 'configuredNeverCloned' ? CLONE_NEVER : CLONE_UNKNOWN
  return (
    <div className={`clone-status clone-${view.kind}`} data-testid={TAG[view.kind]} role="status">
      <span>{text}</span>
    </div>
  )
}
