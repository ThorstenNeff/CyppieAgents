// CYP-405 (W7) — the operator gate + open-warning around the shell. Two independent protections:
//  1. operator-only: a non-operator never gets the affordance (fail-closed mirror of the server gate, CYP-394).
//  2. an open-WARNING shown EVERY time before the shell mounts (Auftraggeber wish): a real shell in the worktree
//     (file access, git, arbitrary commands) exposed to a browser tab is a deliberate act, not a stray click.
// The warning is UX, not access control — the server enforces access; this makes the risk explicit each time.
// `children` (the XtermView) mounts ONLY while open; closing unmounts it (disposing the socket/PTY).
import { useState } from 'react'

type Phase = 'closed' | 'warning' | 'open'

export function ShellGate({ operator, children }: { operator: boolean; children: React.ReactNode }) {
  const [phase, setPhase] = useState<Phase>('closed')

  if (!operator) {
    return (
      <p className="shell-denied" data-testid="shell-denied">
        Die Shell ist nur für Operatoren verfügbar.
      </p>
    )
  }

  if (phase === 'closed') {
    return (
      <button className="shell-open" data-testid="shell-open" onClick={() => setPhase('warning')}>
        Shell öffnen
      </button>
    )
  }

  if (phase === 'warning') {
    return (
      <div className="shell-warning" role="alertdialog" aria-label="Shell-Sicherheitswarnung" data-testid="shell-warning">
        <p>
          ⚠ Dies öffnet eine <strong>echte Shell</strong> im Arbeitsverzeichnis des Agenten — Dateizugriff, git und
          beliebige Kommandos, direkt aus diesem Browser-Tab. Nur öffnen, wenn du das bewusst brauchst.
        </p>
        <button className="shell-cancel" data-testid="shell-cancel" onClick={() => setPhase('closed')}>
          Abbrechen
        </button>
        <button className="shell-confirm" data-testid="shell-confirm" onClick={() => setPhase('open')}>
          Verstanden, öffnen
        </button>
      </div>
    )
  }

  return (
    <div className="shell-open-wrap" data-testid="shell-open-wrap">
      <button className="shell-close" data-testid="shell-close" onClick={() => setPhase('closed')}>
        Shell schließen
      </button>
      {children}
    </div>
  )
}
