// @vitest-environment jsdom
// CYP-659 — render teeth for the Cross-Project Channel-Share panel: operator gate (member sees badge/status but no
// grantee editor / authorize / revoke), grantee candidates exclude the owner project, non-optimistic share re-sync
// from the echo, empty-save = a confirmed revoke (not silent), client-owned revoke confirm, and the first-class
// operator_required error. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent, waitFor } from '@testing-library/react'
import { ChannelSharePanel, type ChannelSharePanelProps } from './ChannelSharePanel'
import { RestError } from '../net/rest'
import { SHARE_OPERATOR_REQUIRED } from './channelShareModel'
import type { Channel, ChannelShareView, Project } from '../types/generated/contract'

const CH: Channel = { id: 'c1', name: 'Kanal 1', kind: 'GROUP', members: [], projectId: 'team-1' }
const PROJECTS: Project[] = [{ id: 'team-1', name: 'Team 1' }, { id: 'team-2', name: 'Team 2' }, { id: 'team-3', name: 'Team 3' }]
const q = (c: HTMLElement, id: string) => c.querySelector<HTMLElement>(`[data-testid="${id}"]`)
const opErr = () => new RestError(403, 'PUT', '/api/channels/c1/share', JSON.stringify({ error: { code: SHARE_OPERATOR_REQUIRED, message: 'op' } }))

function mkProps(over: Partial<ChannelSharePanelProps> = {}): ChannelSharePanelProps {
  return {
    channels: [CH],
    projects: PROJECTS,
    operator: true,
    getShare: vi.fn().mockResolvedValue({ shared: false } as ChannelShareView),
    onShare: vi.fn().mockResolvedValue({ shared: true, sharedAt: 0, reachableScope: [{ agentId: 'a', projectId: 'team-2', access: 'READ' }] } as ChannelShareView),
    onUnshare: vi.fn().mockResolvedValue({ shared: false } as ChannelShareView),
    ...over,
  }
}

describe('ChannelSharePanel — operator gate', () => {
  it('member: badge + status show (read-tier), but NO grantee editor / save / revoke; gate hint shows', async () => {
    const { container } = render(<ChannelSharePanel {...mkProps({ operator: false }, )} />)
    await waitFor(() => expect(q(container, 'channelShare.badge.c1')).not.toBeNull())
    expect(q(container, 'channelShare.gateHint')).not.toBeNull()
    expect(q(container, 'channelShare.status.c1')).not.toBeNull()
    expect(q(container, 'channelShare.grantee.c1.team-2')).toBeNull()
    expect(q(container, 'channelShare.save.c1')).toBeNull()
    expect(q(container, 'channelShare.revoke.c1')).toBeNull()
  })

  it('operator: grantee candidates EXCLUDE the owner project (team-1); team-2/team-3 offered', async () => {
    const { container } = render(<ChannelSharePanel {...mkProps()} />)
    await waitFor(() => expect(q(container, 'channelShare.grantee.c1.team-2')).not.toBeNull())
    expect(q(container, 'channelShare.grantee.c1.team-3')).not.toBeNull()
    expect(q(container, 'channelShare.grantee.c1.team-1')).toBeNull() // owner never offered
    expect(q(container, 'channelShare.owner.c1')!.textContent).toContain('team-1')
  })
})

describe('ChannelSharePanel — share (non-optimistic)', () => {
  it('select a grantee → save → onShare(sanitized); badge flips to shared from the ECHO, revoke appears', async () => {
    const props = mkProps()
    const { container } = render(<ChannelSharePanel {...props} />)
    const box = (await waitFor(() => q(container, 'channelShare.grantee.c1.team-2'))) as HTMLInputElement
    fireEvent.click(box)
    fireEvent.click(q(container, 'channelShare.save.c1') as HTMLButtonElement)
    await waitFor(() => expect(props.onShare).toHaveBeenCalledWith('c1', ['team-2']))
    // non-optimistic: shared state comes from the echo (shared:true) → badge + revoke reflect it
    await waitFor(() => expect(q(container, 'channelShare.badge.c1')!.textContent).toContain('Geteilt'))
    await waitFor(() => expect(q(container, 'channelShare.revoke.c1')).not.toBeNull())
    expect(q(container, 'channelShare.reach.c1.a')!.textContent).toContain('team-2')
  })

  it('operator_required reject → the first-class error message, no state flip', async () => {
    const props = mkProps({ onShare: vi.fn().mockRejectedValue(opErr()) })
    const { container } = render(<ChannelSharePanel {...props} />)
    fireEvent.click((await waitFor(() => q(container, 'channelShare.grantee.c1.team-2'))) as HTMLInputElement)
    fireEvent.click(q(container, 'channelShare.save.c1') as HTMLButtonElement)
    await waitFor(() => expect(q(container, 'channelShare.error.c1')!.textContent).toContain('Operator'))
    expect(q(container, 'channelShare.badge.c1')!.textContent).toContain('Nicht geteilt') // unchanged
  })
})

describe('ChannelSharePanel — revoke (client-owned confirm)', () => {
  const sharedShare: ChannelShareView = { shared: true, sharedAt: 0, reachableScope: [{ agentId: 'a', projectId: 'team-2', access: 'WRITE' }] }

  it('revoke button → confirm dialog first (no network until confirmed) → confirm calls onUnshare', async () => {
    const props = mkProps({ getShare: vi.fn().mockResolvedValue(sharedShare) })
    const { container } = render(<ChannelSharePanel {...props} />)
    await waitFor(() => expect(q(container, 'channelShare.revoke.c1')).not.toBeNull())
    fireEvent.click(q(container, 'channelShare.revoke.c1') as HTMLButtonElement)
    expect(q(container, 'channelShare.confirm.c1')).not.toBeNull()
    expect(props.onUnshare).not.toHaveBeenCalled() // client owns the confirm (server has no confirm token)
    fireEvent.click(q(container, 'channelShare.confirm.c1.ok') as HTMLButtonElement)
    await waitFor(() => expect(props.onUnshare).toHaveBeenCalledWith('c1'))
    await waitFor(() => expect(q(container, 'channelShare.badge.c1')!.textContent).toContain('Nicht geteilt'))
  })

  it('save with NOTHING selected while shared = a revoke → confirm first, never a silent wipe', async () => {
    const props = mkProps({ getShare: vi.fn().mockResolvedValue(sharedShare) })
    const { container } = render(<ChannelSharePanel {...props} />)
    // loaded shared → team-2 pre-checked; uncheck it so the sanitized set is empty
    const box = (await waitFor(() => q(container, 'channelShare.grantee.c1.team-2'))) as HTMLInputElement
    await waitFor(() => expect(box.checked).toBe(true))
    fireEvent.click(box) // uncheck
    fireEvent.click(q(container, 'channelShare.save.c1') as HTMLButtonElement)
    expect(q(container, 'channelShare.confirm.c1')).not.toBeNull() // empty-save routed through the revoke confirm
    expect(props.onShare).not.toHaveBeenCalled()
    fireEvent.click(q(container, 'channelShare.confirm.c1.ok') as HTMLButtonElement)
    await waitFor(() => expect(props.onShare).toHaveBeenCalledWith('c1', [])) // confirmed empty PUT = revoke
  })
})

describe('ChannelSharePanel — load failure (fail-closed)', () => {
  it('a failed share load → error line + no save control acting on a guessed state', async () => {
    const props = mkProps({ getShare: vi.fn().mockRejectedValue(new Error('boom')) })
    const { container } = render(<ChannelSharePanel {...props} />)
    await waitFor(() => expect(q(container, 'channelShare.loadError.c1')).not.toBeNull())
    expect(q(container, 'channelShare.save.c1')).toBeNull()
  })
})
