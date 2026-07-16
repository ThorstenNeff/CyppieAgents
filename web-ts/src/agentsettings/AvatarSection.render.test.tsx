// @vitest-environment jsdom
// CYP-658 — render teeth for the Avatar section inside the Per-Agent-Settings panel: operator-gate (member = current
// avatar + credits only, no upload/grid/remove), the fail-closed upload (bad type / oversize → error, NO network
// call; server reject → GENERIC only, never a fabricated why), the preset write, the client-owned remove confirm,
// the same-origin serve/preview <img> URLs, and the CC-BY credits. Container-scoped (no auto-cleanup in this repo).
import { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent, waitFor } from '@testing-library/react'
import { AgentSettingsPanel, type AgentSettingsPanelProps } from './AgentSettingsPanel'
import { MAX_UPLOAD_BYTES } from './avatarModel'
import type { Agent, AgentDetail } from '../types/generated/contract'

const AGENTS: Agent[] = [{ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend' }]
const q = (c: HTMLElement, id: string) => c.querySelector<HTMLElement>(`[data-testid="${id}"]`)

function mkProps(over: Partial<AgentSettingsPanelProps> = {}, detailOver: Partial<AgentDetail> = {}): AgentSettingsPanelProps {
  const detail: AgentDetail = { id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', launch: 'bash', worktreePath: '/wt', ...detailOver }
  return {
    agents: AGENTS,
    operator: true,
    apiBase: 'http://h',
    fetchDetail: vi.fn().mockResolvedValue(detail),
    onSaveColor: vi.fn().mockResolvedValue(undefined),
    getClaudeMd: vi.fn().mockResolvedValue({ agentId: 'frontend', content: '', exists: false }),
    updateClaudeMd: vi.fn().mockResolvedValue({ agentId: 'frontend', content: '', exists: true, version: 'v1' }),
    onSetAvatarPreset: vi.fn().mockResolvedValue({ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', avatar: { style: 'bottts', seed: 'frontend', type: 'preset' } }),
    onUploadAvatar: vi.fn().mockResolvedValue({ ...detail, avatar: { ref: 'blob-9', type: 'upload' } }),
    onRemoveAvatar: vi.fn().mockResolvedValue(undefined),
    ...over,
  }
}

const pngFile = (name = 'a.png', type = 'image/png', size = 1024): File => {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}

describe('AvatarSection — operator gate', () => {
  it('operator sees the preset grid, upload, and credits', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    await waitFor(() => expect(q(container, 'agentSettings.avatar.section')).not.toBeNull())
    expect(q(container, 'agentSettings.avatar.preset')).not.toBeNull()
    expect(q(container, 'agentSettings.avatar.upload')).not.toBeNull()
    expect(q(container, 'agentSettings.avatar.credits')).not.toBeNull()
  })

  it('member: NO grid / upload / remove, but the current avatar + credits still show (present, not omitted)', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps({ operator: false }, { avatar: { style: 'bottts', seed: 'frontend', type: 'preset' } })} />)
    await waitFor(() => expect(q(container, 'agentSettings.avatar.section')).not.toBeNull())
    expect(q(container, 'agentSettings.avatar.preset')).toBeNull()
    expect(q(container, 'agentSettings.avatar.upload')).toBeNull()
    expect(q(container, 'agentSettings.avatar.remove')).toBeNull()
    expect(q(container, 'agentSettings.avatar.current')).not.toBeNull()
    expect(q(container, 'agentSettings.avatar.credits')).not.toBeNull()
  })
})

describe('AvatarSection — credits (CC-BY)', () => {
  it('lists all 5 styles; CC-BY carries "(bearbeitet)" + a license link', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    await waitFor(() => expect(q(container, 'agentSettings.avatar.credits')).not.toBeNull())
    for (const s of ['bottts', 'avataaars', 'adventurer', 'big-smile', 'fun-emoji']) {
      expect(q(container, `agentSettings.avatar.credits.entry-${s}`), s).not.toBeNull()
    }
    const cc = q(container, 'agentSettings.avatar.credits.entry-big-smile')!
    expect(cc.textContent).toContain('(bearbeitet)')
    expect(cc.querySelector('a')?.getAttribute('href')).toContain('creativecommons.org/licenses/by/4.0')
    // a courtesy-credit (non-CC-BY) style must NOT claim a modification
    expect(q(container, 'agentSettings.avatar.credits.entry-bottts')!.textContent).not.toContain('bearbeitet')
  })
})

describe('AvatarSection — current avatar render', () => {
  it('null avatar → initials fallback (no image, stage=initials)', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    const cur = (await waitFor(() => q(container, 'agentSettings.avatar.current'))) as HTMLElement
    expect(cur.getAttribute('data-stage')).toBe('initials')
    expect(cur.querySelector('img')).toBeNull()
    expect(cur.textContent).toContain('FR') // Frontend → FR
  })
  it('preset avatar → same-origin serve <img> with the cache-bust token', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps({}, { avatar: { style: 'bottts', seed: 'frontend', type: 'preset' } })} />)
    const cur = (await waitFor(() => q(container, 'agentSettings.avatar.current'))) as HTMLElement
    await waitFor(() => expect(cur.querySelector('img')).not.toBeNull())
    const src = cur.querySelector('img')!.getAttribute('src')!
    expect(src).toBe('http://h/api/agents/frontend/avatar?v=bottts-frontend')
    expect(src).not.toContain('dicebear')
  })
})

describe('AvatarSection — upload fail-closed', () => {
  it('wrong type → error, NO upload call (never touches the network)', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    const input = (await waitFor(() => q(container, 'agentSettings.avatar.fileInput'))) as HTMLInputElement
    fireEvent.change(input, { target: { files: [pngFile('a.gif', 'image/gif')] } })
    expect(q(container, 'agentSettings.avatar.uploadError')).not.toBeNull()
    expect(props.onUploadAvatar).not.toHaveBeenCalled()
  })
  it('oversize → error, NO upload call', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    const input = (await waitFor(() => q(container, 'agentSettings.avatar.fileInput'))) as HTMLInputElement
    fireEvent.change(input, { target: { files: [pngFile('big.png', 'image/png', MAX_UPLOAD_BYTES + 1)] } })
    expect(q(container, 'agentSettings.avatar.uploadError')).not.toBeNull()
    expect(props.onUploadAvatar).not.toHaveBeenCalled()
  })
  it('valid file → uploads, re-syncs the avatar from the server echo', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    const input = (await waitFor(() => q(container, 'agentSettings.avatar.fileInput'))) as HTMLInputElement
    fireEvent.change(input, { target: { files: [pngFile()] } })
    await waitFor(() => expect(props.onUploadAvatar).toHaveBeenCalledWith('frontend', expect.any(File)))
    await waitFor(() => expect(q(container, 'agentSettings.avatar.current')!.getAttribute('data-stage')).toBe('image'))
  })
  it('server reject → the GENERIC error only (never a fabricated type/size reason)', async () => {
    const props = mkProps({ onUploadAvatar: vi.fn().mockRejectedValue(new Error('avatar_rejected')) })
    const { container } = render(<AgentSettingsPanel {...props} />)
    const input = (await waitFor(() => q(container, 'agentSettings.avatar.fileInput'))) as HTMLInputElement
    fireEvent.change(input, { target: { files: [pngFile()] } })
    await waitFor(() => expect(q(container, 'agentSettings.avatar.uploadError')!.textContent).toBe('Hochladen fehlgeschlagen.'))
  })
})

describe('AvatarSection — preset + remove', () => {
  it('picking a preset writes presetFor(style, agentId)', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    const cell = (await waitFor(() => q(container, 'agentSettings.avatar.presetStyle.adventurer'))) as HTMLButtonElement
    fireEvent.click(cell)
    await waitFor(() => expect(props.onSetAvatarPreset).toHaveBeenCalledWith('frontend', { style: 'adventurer', seed: 'frontend', type: 'preset' }))
  })
  it('remove → client confirm dialog → confirm calls onRemove and clears to the fallback', async () => {
    const onRemoveAvatar = vi.fn().mockResolvedValue(undefined)
    const props = mkProps({ onRemoveAvatar }, { avatar: { style: 'bottts', seed: 'frontend', type: 'preset' } })
    const { container } = render(<AgentSettingsPanel {...props} />)
    await waitFor(() => expect(q(container, 'agentSettings.avatar.remove')).not.toBeNull())
    fireEvent.click(q(container, 'agentSettings.avatar.remove') as HTMLButtonElement)
    // client owns the confirm (DELETE is idempotent, no server confirm token) — no network call until confirmed
    expect(q(container, 'agentSettings.avatar.removeConfirm')).not.toBeNull()
    expect(onRemoveAvatar).not.toHaveBeenCalled()
    fireEvent.click(q(container, 'agentSettings.avatar.removeConfirm.confirm') as HTMLButtonElement)
    await waitFor(() => expect(onRemoveAvatar).toHaveBeenCalledWith('frontend'))
    await waitFor(() => expect(q(container, 'agentSettings.avatar.current')!.getAttribute('data-stage')).toBe('initials'))
  })
})

// CYP-658 (churn-immunity, per CYP-660) — the parent passes a NEW fetchDetail identity every render. The section must
// NOT re-fetch/reset the avatar on a WS-tick re-render. Pinned via a VISIBLE outcome isolated from the (unfixed,
// CYP-661) Color/Worktree siblings: after picking a preset the selection ring must SURVIVE a churn re-render.
// Mutation = revert the AvatarSection effect deps to [agentId, fetchDetail] → the re-fetch resets avatar → ring lost → RED.
function AvatarChurnHarness({
  fetchSpy,
  onSetPreset,
}: {
  fetchSpy: (id: string) => Promise<AgentDetail>
  onSetPreset: AgentSettingsPanelProps['onSetAvatarPreset']
}) {
  const [tick, setTick] = useState(0)
  return (
    <div>
      <button data-testid="ws-tick" onClick={() => setTick((t) => t + 1)}>
        {tick}
      </button>
      <AgentSettingsPanel
        agents={AGENTS}
        operator={true}
        apiBase="http://h"
        // ★ INLINE arrow → NEW identity every render (reproduces the App.tsx churn).
        fetchDetail={(id) => fetchSpy(id)}
        onSaveColor={() => Promise.resolve()}
        getClaudeMd={() => Promise.resolve({ agentId: 'frontend', content: '', exists: false })}
        updateClaudeMd={() => Promise.resolve({ agentId: 'frontend', content: '', exists: true, version: 'v1' })}
        onSetAvatarPreset={onSetPreset}
        onUploadAvatar={() => Promise.resolve({ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', launch: 'bash' })}
        onRemoveAvatar={() => Promise.resolve()}
      />
    </div>
  )
}

describe('AvatarSection — churn-immune (CYP-660 cure)', () => {
  it('a picked preset survives WS-tick re-renders (avatar NOT re-fetched/reset on callback churn)', async () => {
    // the server has NO avatar initially; picking a preset returns the preset echo.
    const fetchSpy = vi.fn<(id: string) => Promise<AgentDetail>>().mockResolvedValue({ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', launch: 'bash' })
    const onSetPreset = vi.fn().mockResolvedValue({ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', avatar: { style: 'adventurer', seed: 'frontend', type: 'preset' } })
    const { container } = render(<AvatarChurnHarness fetchSpy={fetchSpy} onSetPreset={onSetPreset} />)

    const cell = (await waitFor(() => {
      const c = q(container, 'agentSettings.avatar.presetStyle.adventurer')
      if (!c) throw new Error('no grid')
      return c
    })) as HTMLButtonElement
    fireEvent.click(cell)
    await waitFor(() => expect(q(container, 'agentSettings.avatar.presetStyle.adventurer')!.getAttribute('aria-pressed')).toBe('true'))

    // WS-tick re-renders churn the fetchDetail identity; a churn-immune section must not re-fetch → the ring stays.
    const tick = q(container, 'ws-tick') as HTMLButtonElement
    fireEvent.click(tick)
    fireEvent.click(tick)
    await waitFor(() => expect((q(container, 'ws-tick') as HTMLButtonElement).textContent).toBe('2'))

    expect(q(container, 'agentSettings.avatar.presetStyle.adventurer')!.getAttribute('aria-pressed')).toBe('true')
  })
})
