// CYP-735 §3.2 (UIUX2 screen-spec 951823ab) — the guided first-run gate: Intro + stepper + skip.
//
// MOUNT CHAIN: AuthGate → FirstRunGate → Workspace. The mode is derived PURELY from the config status
// (firstRunModel), so there is no second source of truth to drift from the banner in §3.1.
//
// THREE MODES, and the honesty sits in the first one:
//   • loading      — the config has not answered (or failed). A load surface, NEVER "step 1" and never
//                    "unconfigured": showing setup steps because we could not read the config would tell a
//                    configured operator to set up their hub.
//   • active       — something genuinely left to do → the steps.
//   • transparent  — both core steps done → the gate is not rendered at all.
//
// BLOCKING BUT ESCAPABLE (spec §2): the gate covers the workspace by default, because dropping a new operator into
// an empty workspace whose agents cannot start is its own kind of dishonesty. But it is skippable, and skipping
// leads to the REAL workspace carrying the §3.1 banner + agent-start gating — never a "clean" workspace that
// implies everything is fine. Guidance may be dismissed; the truth may not.
import type { ReactNode } from 'react'
import { firstRunGateMode, firstRunProgress, type FirstRunInputs, type StepState } from './firstRunModel'
import {
  FIRST_RUN_INTRO,
  FIRST_RUN_SKIP,
  FIRST_RUN_SKIP_NOTE,
  FIRST_RUN_STEP,
  FIRST_RUN_TITLE,
  STEP_STATE_LABEL,
} from './firstRunText'

export interface FirstRunGateProps {
  inputs: FirstRunInputs
  /** Rendered for the API-key step — the existing ApiKeyPanel, reused rather than re-implemented. */
  apiKeyStep: ReactNode
  /** Rendered for the repo step — the existing repo-config form. */
  repoStep: ReactNode
  /** Skip → degraded workspace. Whether the skip is REMEMBERED is the caller's decision, not the gate's. */
  onSkip: () => void
  /** Load-surface retry, used in `loading` when the config read failed. */
  onRetry: () => void
  loadErrorSurface: ReactNode
  children: ReactNode
}

function StepStatus({ id, state }: { id: string; state: StepState }) {
  return (
    <span className={`firstrun-step-status firstrun-step-${state}`} data-testid={`firstrun.step.${id}.status`} data-state={state}>
      {STEP_STATE_LABEL[state]}
    </span>
  )
}

export function FirstRunGate({ inputs, apiKeyStep, repoStep, onSkip, loadErrorSurface, children }: FirstRunGateProps) {
  const mode = firstRunGateMode(inputs)
  if (mode === 'transparent') return <>{children}</>

  if (mode === 'loading') {
    // NOT a step: an unresolved config gets its own surface. A failed read shows error+retry (CYP-288/679),
    // never "set up your hub".
    return (
      <div className="firstrun-gate firstrun-loading" data-testid="firstrun.gate" data-mode="loading">
        {loadErrorSurface}
      </div>
    )
  }

  const progress = firstRunProgress(inputs)
  return (
    <div className="firstrun-gate" data-testid="firstrun.gate" data-mode="active" role="region" aria-label={FIRST_RUN_TITLE}>
      <h2 className="firstrun-title">{FIRST_RUN_TITLE}</h2>
      <p className="firstrun-intro">{FIRST_RUN_INTRO}</p>

      <ol className="firstrun-steps">
        <li className="firstrun-step" data-testid="firstrun.step.apikey">
          <span className="firstrun-step-name">{FIRST_RUN_STEP.apikey}</span>
          <StepStatus id="apikey" state={progress.apiKey} />
          <div className="firstrun-step-body">{apiKeyStep}</div>
        </li>
        <li className="firstrun-step" data-testid="firstrun.step.repo">
          <span className="firstrun-step-name">{FIRST_RUN_STEP.repo}</span>
          <StepStatus id="repo" state={progress.repo} />
          <div className="firstrun-step-body">{repoStep}</div>
        </li>
        {/* Team is INFORMATIONAL (spec §3/§6.5) — it never gates completion, so it carries no form and can never
            block anyone from reaching their workspace for not having configured agents yet. */}
        <li className="firstrun-step" data-testid="firstrun.step.team">
          <span className="firstrun-step-name">{FIRST_RUN_STEP.team}</span>
          <StepStatus id="team" state={progress.team} />
        </li>
      </ol>

      <button type="button" className="firstrun-skip" data-testid="firstrun.skip" onClick={onSkip}>
        {FIRST_RUN_SKIP}
      </button>
      <p className="firstrun-skip-note" role="note">
        {FIRST_RUN_SKIP_NOTE}
      </p>
    </div>
  )
}
