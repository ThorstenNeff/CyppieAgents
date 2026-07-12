// CYP-431 (P2-a) — the agent-window status/lifecycle header: a status dot (CYP-396 spec) + label + operator-gated
// start/stop/restart controls. Honesty: the status is server-confirmed (non-optimistic — the caller passes the feed
// state + a transient pending); colour is never the sole signal (the text label carries it). Operator gate: for a
// non-operator the controls are PRESENT but DISABLED (no fake affordance, CYP-317), never hidden. Layout (CYP-369):
// the controls cluster is flex-shrink:0 so every control keeps width>0 at a narrow window; the label cluster shrinks.
import {
  statusDotSpec,
  dotRoleVar,
  lifecycleLabel,
  lifecycleControlEnabled,
  errorReasonText,
  type LifecycleState,
} from './lifecycleStatus'
import type { LifecycleAction, AgentErrorCode } from '../state/hubReducers'

export interface LifecycleHeaderProps {
  agentId: string
  state: LifecycleState
  pending: LifecycleAction | undefined
  operator: boolean
  /** CYP-445: a transient reject notice for the last action (409/503/…), separate from the agent's own ERROR state. */
  error?: string | null
  /** CYP-446: the durable ERROR-state reason code; shown as a curated sentence ONLY in ERROR, its own node. */
  errorCode?: AgentErrorCode
  onStart: (agentId: string) => void
  onStop: (agentId: string) => void
  onRestart: (agentId: string) => void
}

export function LifecycleHeader({ agentId, state, pending, operator, error = null, errorCode, onStart, onStop, onRestart }: LifecycleHeaderProps) {
  const spec = statusDotSpec(state, pending !== undefined)
  const label = lifecycleLabel(state, pending)
  const color = dotRoleVar(spec.role)
  const dotStyle =
    spec.shape === 'fill'
      ? { width: 8, height: 8, borderRadius: '50%', background: color }
      : { width: 8, height: 8, borderRadius: '50%', border: `2px solid ${color}`, boxSizing: 'border-box' as const }

  // CYP-445 §5: per-control enablement (Start⇔≠RUNNING / Stopp⇔=RUNNING / Neustart⇔operator), not a single flag.
  const isPending = pending !== undefined
  const startEnabled = lifecycleControlEnabled('start', state, operator, isPending)
  const stopEnabled = lifecycleControlEnabled('stop', state, operator, isPending)
  const restartEnabled = lifecycleControlEnabled('restart', state, operator, isPending)

  return (
    <header className="lifecycle-header" data-testid={`lifecycle.header.${agentId}`}>
      <span className="lifecycle-status" role="status" aria-label={`Status: ${label}`} data-testid={`lifecycle.status.${agentId}`}>
        <span
          className="lifecycle-dot"
          aria-hidden="true"
          data-testid={`lifecycle.dot.${agentId}`}
          data-shape={spec.shape}
          data-role={spec.role}
          style={dotStyle}
        />
        <span className="lifecycle-label">{label}</span>
      </span>

      <div className="lifecycle-controls" data-testid={`lifecycle.controls.${agentId}`}>
        <button
          type="button"
          className="lifecycle-btn"
          data-testid={`lifecycle.start.${agentId}`}
          disabled={!startEnabled}
          aria-disabled={!startEnabled}
          onClick={() => onStart(agentId)}
        >
          Start
        </button>
        <button
          type="button"
          className="lifecycle-btn"
          data-testid={`lifecycle.stop.${agentId}`}
          disabled={!stopEnabled}
          aria-disabled={!stopEnabled}
          onClick={() => onStop(agentId)}
        >
          Stopp
        </button>
        <button
          type="button"
          className="lifecycle-btn"
          data-testid={`lifecycle.restart.${agentId}`}
          disabled={!restartEnabled}
          aria-disabled={!restartEnabled}
          // Restart-on-key-change: a persona/API-key change takes effect on the NEXT spawn (restart to apply it).
          title="Neustart übernimmt geänderte Persona/API-Key beim nächsten Spawn"
          onClick={() => onRestart(agentId)}
        >
          Neustart
        </button>
        {!operator && (
          <span className="lifecycle-operator-only" role="note" data-testid={`lifecycle.operatorOnly.${agentId}`}>
            Nur Operatoren steuern den Lebenszyklus.
          </span>
        )}
      </div>

      {error !== null && (
        <p className="lifecycle-error" role="alert" data-testid={`lifecycle.error.${agentId}`}>
          {error}
        </p>
      )}

      {/* CYP-446: the durable ERROR-state reason — its OWN node, present iff ERROR (never merged with the transient
          action-error above). Curated sentence per code, fail-closed to "reason not reported" for unknown/absent. */}
      {state === 'ERROR' && (
        <p className="lifecycle-error-reason" role="note" data-testid={`lifecycle.errorReason.${agentId}`}>
          {errorReasonText(errorCode)}
        </p>
      )}
    </header>
  )
}
