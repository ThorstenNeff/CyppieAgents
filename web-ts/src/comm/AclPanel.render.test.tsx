// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { AclPanel, type AclPanelProps } from './AclPanel'
import type { AclEntry, Channel } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [{ id: 'po-frontend', name: 'PO ↔ FE', kind: 'DIRECT', members: ['po', 'frontend'] }]
const agents = ['frontend', 'po']

const base = (over: Partial<AclPanelProps> = {}): AclPanelProps => ({
  channels,
  agents,
  entries: [
    { channelId: 'po-frontend', agentId: 'po', canRead: true, canWrite: true },
    { channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: true },
  ],
  pending: new Map(),
  poAgentId: 'po',
  operator: true,
  onCommit: vi.fn(),
  ...over,
})

describe('AclPanel — W9 dialog wiring (CYP-425)', () => {
  it('a non-PO toggle commits directly, with no dialog', () => {
    const onCommit = vi.fn()
    const { getByTestId, queryByTestId } = render(<AclPanel {...base({ onCommit })} />)
    fireEvent.click(getByTestId('acl.cell.po-frontend.frontend.write')) // true -> false
    expect(queryByTestId('acl-lockout-dialog')).toBeNull()
    expect(onCommit).toHaveBeenCalledWith({ channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: false }, ['write'])
  })

  it('removing the PO’s own read opens the lockout dialog and does NOT commit until confirmed', () => {
    const onCommit = vi.fn()
    const { getByTestId, queryByTestId } = render(<AclPanel {...base({ onCommit })} />)
    fireEvent.click(getByTestId('acl.cell.po-frontend.po.read')) // PO read true -> false = lockout risk
    const dialog = getByTestId('acl-lockout-dialog')
    expect(dialog).toBeTruthy()
    // CYP-468 micro: this is a WARN, so it must NOT carry the event-log ERROR glyph ⚠ (WARN glyph is ▲, aria-hidden).
    expect(dialog.textContent).not.toContain('⚠')
    expect(onCommit).not.toHaveBeenCalled() // advisory: nothing committed yet
    fireEvent.click(getByTestId('acl-lockout-cancel'))
    expect(queryByTestId('acl-lockout-dialog')).toBeNull()
    expect(onCommit).not.toHaveBeenCalled() // cancel = no change
  })

  it('confirming the lockout dialog commits exactly the PO read-off entry', () => {
    const onCommit = vi.fn()
    const { getByTestId } = render(<AclPanel {...base({ onCommit })} />)
    fireEvent.click(getByTestId('acl.cell.po-frontend.po.read'))
    fireEvent.click(getByTestId('acl-lockout-confirm'))
    expect(onCommit).toHaveBeenCalledWith({ channelId: 'po-frontend', agentId: 'po', canRead: false, canWrite: true }, ['read'])
  })

  it('the Hub-and-Spoke preset previews the diff count and applies non-atomically (one commit per changed cell)', () => {
    const onCommit = vi.fn()
    // frontend.write is off → the preset (all members read+write) changes exactly that one cell.
    const entries: AclEntry[] = [
      { channelId: 'po-frontend', agentId: 'po', canRead: true, canWrite: true },
      { channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: false },
    ]
    const { getByTestId } = render(<AclPanel {...base({ onCommit, entries })} />)
    fireEvent.click(getByTestId('acl-preset-open'))
    expect(getByTestId('acl-preset-count').textContent).toContain('1 Zelle ändert')
    fireEvent.click(getByTestId('acl-preset-apply'))
    expect(onCommit).toHaveBeenCalledTimes(1)
    expect(onCommit).toHaveBeenCalledWith({ channelId: 'po-frontend', agentId: 'frontend', canRead: true, canWrite: true }, ['write'])
  })

  it('surfaces a transient reject notice when error is set (CYP-435), and hides it otherwise', () => {
    const none = render(<AclPanel {...base()} />)
    expect(none.queryByTestId('acl-error')).toBeNull()
    cleanup()
    const rejected = render(<AclPanel {...base()} error="Vom Hub abgelehnt (PO-Aussperrschutz aktiv)." />)
    expect(rejected.getByTestId('acl-error').textContent).toContain('PO-Aussperrschutz')
    expect(rejected.getByTestId('acl-error').getAttribute('role')).toBe('alert')
  })

  it('read-only (non-operator) shows chips and no operator actions', () => {
    const { queryByTestId, getByTestId } = render(<AclPanel {...base({ operator: false })} />)
    expect(queryByTestId('acl-preset-open')).toBeNull()
    // the matrix renders read-only chips (a <span>), never a role=switch button
    expect(getByTestId('acl.cell.po-frontend.frontend.read').tagName).toBe('SPAN')
  })
})
