// @vitest-environment jsdom
// CYP-650 — render teeth for the workspace roster panel. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
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

describe('WorkspaceRosterPanel — CYP-679 honest load-error + retry (per section)', () => {
  it('members load-fail → members error+retry (NOT "Keine Mitglieder"); audit section unaffected; retry fires', () => {
    const onRetryMembers = vi.fn()
    const { container } = render(
      <WorkspaceRosterPanel members={[]} audit={audit} membersLoadError onRetryMembers={onRetryMembers} />,
    )
    expect(q(container, 'workspace-roster.members.loadError')).not.toBeNull()
    expect(q(container, 'workspace-roster.members-empty')).toBeNull() // mutation: empty over error → RED
    expect(q(container, 'workspace-roster.audit')).not.toBeNull() // the other section is independent
    fireEvent.click(q(container, 'workspace-roster.members.loadError.retry') as HTMLElement)
    expect(onRetryMembers).toHaveBeenCalledTimes(1)
  })

  it('audit load-fail → audit error+retry (NOT "Keine Aktionen"); members section unaffected; retry fires', () => {
    const onRetryAudit = vi.fn()
    const { container } = render(
      <WorkspaceRosterPanel members={members} audit={[]} auditLoadError onRetryAudit={onRetryAudit} />,
    )
    expect(q(container, 'workspace-roster.audit.loadError')).not.toBeNull()
    expect(q(container, 'workspace-roster.audit-empty')).toBeNull()
    expect(q(container, 'workspace-roster.members')).not.toBeNull()
    fireEvent.click(q(container, 'workspace-roster.audit.loadError.retry') as HTMLElement)
    expect(onRetryAudit).toHaveBeenCalledTimes(1)
  })

  it('genuinely-empty (no error) → honest empty markers, not the error (non-vacuum contrast)', () => {
    const { container } = render(<WorkspaceRosterPanel members={[]} audit={[]} />)
    expect(q(container, 'workspace-roster.members-empty')).not.toBeNull()
    expect(q(container, 'workspace-roster.audit-empty')).not.toBeNull()
    expect(q(container, 'workspace-roster.members.loadError')).toBeNull()
    expect(q(container, 'workspace-roster.audit.loadError')).toBeNull()
  })

  it('data present + error flag → lists render, error hidden (flag 4)', () => {
    const { container } = render(<WorkspaceRosterPanel members={members} audit={audit} membersLoadError auditLoadError />)
    expect(q(container, 'workspace-roster.members')).not.toBeNull()
    expect(q(container, 'workspace-roster.members.loadError')).toBeNull()
  })
})
