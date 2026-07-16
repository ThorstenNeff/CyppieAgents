// @vitest-environment jsdom
// CYP-650 — render teeth for the workspace roster panel. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { WorkspaceRosterPanel } from './WorkspaceRosterPanel'
import type { WorkspaceMember, OperatorAudit } from '../types/generated/contract'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)
const members: WorkspaceMember[] = [
  { identityId: 'operatorId12345', tier: 'OPERATOR', displayName: null },
  { identityId: 'memberIdABCDEFG', tier: 'MEMBER', displayName: 'Robin' },
]
const audit: OperatorAudit[] = [{ actor: 'op', method: 'POST', path: '/api/agents/backend/start', tsMs: 1_700_000_000_000 }]

describe('WorkspaceRosterPanel (render)', () => {
  it('lists members with short labels + tier TEXT (no full identity leak)', () => {
    const { container } = render(<WorkspaceRosterPanel members={members} audit={audit} />)
    // short id prefix, never the full identityId
    expect(q(container, 'workspace-roster.member.operatorId12345')?.textContent).toContain('operator') // first 8 = "operato" + "r"
    expect(container.textContent).not.toContain('operatorId12345')
    expect(q(container, 'workspace-roster.tier.operatorId12345')?.textContent).toBe('Operator')
    // displayName wins when present
    expect(q(container, 'workspace-roster.member.memberIdABCDEFG')?.textContent).toContain('Robin')
    expect(q(container, 'workspace-roster.tier.memberIdABCDEFG')?.textContent).toBe('Mitglied')
  })

  it('renders the content-free audit line (verb + path)', () => {
    const { container } = render(<WorkspaceRosterPanel members={members} audit={audit} />)
    expect(q(container, 'workspace-roster.audit')?.textContent).toContain('POST /api/agents/backend/start')
  })

  it('empty roster / audit → honest empty markers, not a blank pane', () => {
    const { container } = render(<WorkspaceRosterPanel members={[]} audit={[]} />)
    expect(q(container, 'workspace-roster.members-empty')).not.toBeNull()
    expect(q(container, 'workspace-roster.audit-empty')).not.toBeNull()
    expect(q(container, 'workspace-roster.members')).toBeNull()
  })
})
