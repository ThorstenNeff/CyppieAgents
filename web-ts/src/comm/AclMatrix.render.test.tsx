// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { AclMatrix } from './AclMatrix'
import { pendingKey } from './aclModel'
import type { AclEntry } from '../types/generated/contract'

afterEach(cleanup)

const channels = [{ id: 'po-frontend', members: ['po', 'frontend'] }]
const agents = ['po', 'frontend', 'backend'] // backend is NOT a member of po-frontend

describe('AclMatrix (CYP-407)', () => {
  it('aria-checked is the ENFORCED value, never the optimistic click; pending sets aria-busy', () => {
    const entries: AclEntry[] = [{ channelId: 'po-frontend', agentId: 'frontend', canRead: false, canWrite: true }]
    const pending = new Map([[pendingKey('po-frontend', 'frontend', 'read'), true]]) // a read toggle in flight
    const { getByTestId } = render(
      <AclMatrix channels={channels} agents={agents} entries={entries} pending={pending} poAgentId="po" onToggle={() => {}} />,
    )
    const read = getByTestId('acl.cell.po-frontend.frontend.read')
    expect(read.getAttribute('aria-checked')).toBe('false') // enforced false, NOT the pending click
    expect(read.getAttribute('aria-busy')).toBe('true')
    expect(getByTestId('acl.cell.po-frontend.frontend.write').getAttribute('aria-checked')).toBe('true')
  })

  it('a non-member cell shows the "kein Mitglied" marker, not a disabled switch (grantable, CYP-317)', () => {
    const { queryByTestId } = render(
      <AclMatrix channels={channels} agents={agents} entries={[]} pending={new Map()} poAgentId="po" onToggle={() => {}} />,
    )
    expect(queryByTestId('acl.cell.po-frontend.backend.nonMember')).not.toBeNull()
    expect(queryByTestId('acl.cell.po-frontend.backend.read')).toBeNull() // NO switch for a non-member
  })

  it('toggling a switch requests the OPPOSITE of the enforced value', () => {
    const onToggle = vi.fn()
    const entries: AclEntry[] = [{ channelId: 'po-frontend', agentId: 'po', canRead: true, canWrite: true }]
    const { getByTestId } = render(
      <AclMatrix channels={channels} agents={agents} entries={entries} pending={new Map()} poAgentId="po" onToggle={onToggle} />,
    )
    fireEvent.click(getByTestId('acl.cell.po-frontend.po.read'))
    expect(onToggle).toHaveBeenCalledWith('po-frontend', 'po', 'read', false) // enforced true → requests false
  })

  it('read-only (non-operator) renders chips, not switches', () => {
    const { getByTestId } = render(
      <AclMatrix channels={channels} agents={agents} entries={[]} pending={new Map()} poAgentId="po" onToggle={() => {}} readOnly />,
    )
    const chip = getByTestId('acl.cell.po-frontend.po.read')
    expect(chip.getAttribute('role')).toBeNull() // a span chip, not a role=switch
  })
})
