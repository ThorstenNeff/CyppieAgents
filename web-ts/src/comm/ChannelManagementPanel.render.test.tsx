// @vitest-environment jsdom
// CYP-875 (OS-C) — render teeth. The ones that must BITE (coordinator): 409-protected-HUB + 403-operator shown
// HONESTLY (server-authoritative, never silent/hidden) and optimistic-rollback-on-reject (a rejected mutation reverts
// the UI — no hanging phantom). render ≠ authority.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act } from '@testing-library/react'
import { ChannelManagementPanel } from './ChannelManagementPanel'
import { RestError } from '../net/rest'
import type { Channel } from '../types/generated/contract'

afterEach(cleanup)

const grp: Channel = { id: 'g', name: 'Grp', kind: 'GROUP', members: ['po'] }
const hub: Channel = { id: 'h', name: 'Hub', kind: 'HUB', members: ['po'] }
const noop = { onCreateChannel: vi.fn(), onRenameChannel: vi.fn(), onArchiveChannel: vi.fn() }
const panel = (over: Partial<Parameters<typeof ChannelManagementPanel>[0]> = {}) =>
  render(<ChannelManagementPanel channels={[grp]} agentIds={['frontend', 'backend']} operator {...noop} {...over} />)

describe('CYP-875 — ChannelManagementPanel: honest server errors + optimistic rollback', () => {
  it('★ archive REJECT (409 protected-HUB) → the error is shown HONESTLY and the row is ROLLED BACK (restored, no phantom)', async () => {
    // MUT: swallow the error → no error node → red. MUT: don't restore on reject → the row stays gone → red.
    const onArchiveChannel = vi.fn().mockRejectedValue(new RestError(409, 'DELETE', '/api/channels/g', ''))
    const { getByTestId, queryByTestId } = panel({ onArchiveChannel })
    await act(async () => {
      fireEvent.click(getByTestId('channel-mgmt.archive.g'))
    })
    expect(getByTestId('channel-mgmt.error').textContent).toContain('geschützt') // 409 → protected-HUB, honest
    expect(queryByTestId('channel-mgmt.row.g')).not.toBeNull() // ROLLBACK — the channel is back
  })

  it('★ archive REJECT (403 operator) → the operator-only error is shown honestly', async () => {
    const onArchiveChannel = vi.fn().mockRejectedValue(new RestError(403, 'DELETE', '/api/channels/g', ''))
    const { getByTestId } = panel({ onArchiveChannel })
    await act(async () => {
      fireEvent.click(getByTestId('channel-mgmt.archive.g'))
    })
    expect(getByTestId('channel-mgmt.error').textContent).toContain('Operator')
  })

  it('★ create is OPTIMISTIC: the new channel appears immediately (before the server confirms)', () => {
    const onCreateChannel = vi.fn().mockReturnValue(new Promise<Channel>(() => {})) // never settles
    const { getByTestId, queryByTestId } = panel({ onCreateChannel })
    fireEvent.change(getByTestId('channel-mgmt.create.id'), { target: { value: 'newc' } })
    fireEvent.change(getByTestId('channel-mgmt.create.name'), { target: { value: 'New C' } })
    fireEvent.click(getByTestId('channel-mgmt.create.member.frontend'))
    fireEvent.click(getByTestId('channel-mgmt.create.submit'))
    expect(queryByTestId('channel-mgmt.row.newc')).not.toBeNull() // shown optimistically, before resolve
  })

  it('★ create REJECT → the optimistic channel is ROLLED BACK (removed) + error shown (no phantom)', async () => {
    // MUT: keep the optimistic entry on reject → the phantom hangs → red.
    const onCreateChannel = vi.fn().mockRejectedValue(new RestError(500, 'POST', '/api/channels', ''))
    const { getByTestId, queryByTestId } = panel({ onCreateChannel })
    fireEvent.change(getByTestId('channel-mgmt.create.id'), { target: { value: 'newc' } })
    fireEvent.change(getByTestId('channel-mgmt.create.name'), { target: { value: 'New C' } })
    fireEvent.click(getByTestId('channel-mgmt.create.member.frontend'))
    await act(async () => {
      fireEvent.click(getByTestId('channel-mgmt.create.submit'))
    })
    expect(queryByTestId('channel-mgmt.row.newc')).toBeNull() // rolled back — the phantom is gone
    expect(getByTestId('channel-mgmt.error')).toBeTruthy()
  })

  it('★ a HUB channel’s archive is DISABLED (client hint; server 409 is the authoritative guard)', () => {
    const { getByTestId } = panel({ channels: [hub] })
    expect((getByTestId('channel-mgmt.archive.h') as HTMLButtonElement).disabled).toBe(true)
  })

  it('★ a NON-operator cannot create (affordance disabled) — but a server 403 would still surface honestly', () => {
    const { getByTestId } = panel({ operator: false })
    expect((getByTestId('channel-mgmt.create.submit') as HTMLButtonElement).disabled).toBe(true)
    expect((getByTestId('channel-mgmt.create.id') as HTMLInputElement).disabled).toBe(true)
  })
})
