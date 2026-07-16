// CYP-651 (P8, Epic CYP-640) — the always-visible project switcher in the workspace bar. Parity with CMP
// ProjectSwitcherBar: the active-project context + a quick switch. A native <select> (accessible, keyboard-native);
// the selected option is the active project. Switching is NON-OPTIMISTIC + heavyweight — the select is disabled
// while a switch is pending and the active value follows the server-confirmed `activeProjectId`, never the click.
// Switch-to-active is a no-op (skipped). Operator-gated (only an operator flips the shared active project).
import { useState } from 'react'
import type { ProjectsView } from '../types/generated/contract'
import { switchIsNoop } from './projectModel'

export function ProjectSwitcher({
  projects,
  operator,
  onSwitch,
}: {
  projects: ProjectsView | null
  operator: boolean
  onSwitch: (projectId: string) => Promise<void>
}) {
  const [pending, setPending] = useState(false)
  // Only render when there is a real registry with a choice to make (≥2 projects); a single project needs no switcher.
  if (projects === null || projects.projects.length < 2) return null

  const onChange = (targetId: string) => {
    if (switchIsNoop(targetId, projects.activeProjectId)) return // clicking the current project → nothing to do
    setPending(true)
    void onSwitch(targetId).finally(() => setPending(false))
  }

  return (
    <label className="project-switcher" data-testid="project-switcher">
      <span className="project-switcher-label">Projekt</span>
      <select
        data-testid="project-switcher.select"
        aria-label="Aktives Projekt wechseln"
        // NON-OPTIMISTIC: value mirrors the server active pointer, not an in-flight selection.
        value={projects.activeProjectId}
        disabled={!operator || pending}
        onChange={(e) => onChange(e.target.value)}
      >
        {projects.projects.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </select>
    </label>
  )
}
