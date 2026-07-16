// @vitest-environment jsdom
// CYP-657 — render teeth for the Per-Agent-Settings panel: operator-gate (present-but-disabled), the contrast guard
// (invalid → error + save blocked; degraded → advisory), the CLAUDE.md conflict dialog (409 stale → dialog, no silent
// clobber; reload vs overwrite), the restart-deferred hint, and the worktree failed≠remote zone. Container-scoped
// (no auto-cleanup in this repo).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent, waitFor } from '@testing-library/react'
import { AgentSettingsPanel, type AgentSettingsPanelProps } from './AgentSettingsPanel'
import { RestError } from '../net/rest'
import { CLAUDE_MD_STALE_CODE } from './agentSettingsModel'
import type { Agent, AgentDetail, ClaudeMdView } from '../types/generated/contract'

const AGENTS: Agent[] = [{ id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend' }]

const staleError = () =>
  new RestError(409, 'POST', '/api/agents/frontend/claude-md', JSON.stringify({ error: { code: CLAUDE_MD_STALE_CODE, message: 'stale' } }))

function mkProps(over: Partial<AgentSettingsPanelProps> = {}): AgentSettingsPanelProps {
  const detail: AgentDetail = { id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', launch: 'bash', worktreePath: '/home/agent/worktrees/frontend' }
  const view: ClaudeMdView = { agentId: 'frontend', content: 'PERSONA', exists: true, version: 'v1' }
  return {
    agents: AGENTS,
    operator: true,
    fetchDetail: vi.fn().mockResolvedValue(detail),
    onSaveColor: vi.fn().mockResolvedValue(undefined),
    getClaudeMd: vi.fn().mockResolvedValue(view),
    updateClaudeMd: vi.fn().mockResolvedValue({ ...view, content: 'PERSONA', version: 'v2' }),
    ...over,
  }
}

const q = (c: HTMLElement, id: string) => c.querySelector<HTMLElement>(`[data-testid="${id}"]`)

describe('AgentSettingsPanel — operator gate', () => {
  it('member (non-operator): gate hint shows and mutation controls are disabled — present, not omitted', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps({ operator: false })} />)
    expect(q(container, 'agentSettings.panel')).not.toBeNull()
    expect(q(container, 'agentSettings.gateHint')).not.toBeNull()
    expect(q(container, 'agentSettings.swatch.0')).toHaveProperty('disabled', true)
    expect(q(container, 'agentSettings.customHex.input')).toHaveProperty('disabled', true)
    await waitFor(() => expect(q(container, 'agentSettings.persona.input')).toHaveProperty('disabled', true))
  })
})

describe('AgentSettingsPanel — contrast guard', () => {
  it('invalid hex → error + colour save stays disabled (fail-closed, not persistable)', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    const input = q(container, 'agentSettings.customHex.input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'nope' } })
    expect(q(container, 'agentSettings.customHex.error')).not.toBeNull()
    expect(q(container, 'agentSettings.color.save')).toHaveProperty('disabled', true)
  })

  it('degraded (near-white) → advisory shows', () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    fireEvent.change(q(container, 'agentSettings.customHex.input') as HTMLInputElement, { target: { value: '#F2F2F2' } })
    expect(q(container, 'agentSettings.contrastAdvisory')).not.toBeNull()
  })

  it('a valid swatch → no advisory, preview shows, save enabled → onSaveColor + restart hint', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    fireEvent.click(q(container, 'agentSettings.swatch.0') as HTMLElement)
    expect(q(container, 'agentSettings.contrastAdvisory')).toBeNull()
    expect(q(container, 'agentSettings.customHex.error')).toBeNull()
    expect(q(container, 'agentSettings.preview')).not.toBeNull()
    const save = q(container, 'agentSettings.color.save') as HTMLButtonElement
    expect(save.disabled).toBe(false)
    fireEvent.click(save)
    await waitFor(() => expect(props.onSaveColor).toHaveBeenCalledWith('frontend', expect.stringMatching(/^#[0-9a-f]{6}$/)))
    await waitFor(() => expect(q(container, 'agentSettings.effectHint')).not.toBeNull()) // saved ≠ active (restart-deferred)
  })
})

describe('AgentSettingsPanel — CLAUDE.md', () => {
  it('loads the live persona into the editor', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    await waitFor(() => expect((q(container, 'agentSettings.persona.input') as HTMLTextAreaElement).value).toBe('PERSONA'))
  })

  it('empty file → the empty note (not a blank editor pretending content)', async () => {
    const view: ClaudeMdView = { agentId: 'frontend', content: '', exists: false }
    const { container } = render(<AgentSettingsPanel {...mkProps({ getClaudeMd: vi.fn().mockResolvedValue(view) })} />)
    await waitFor(() => expect(q(container, 'agentSettings.persona.empty')).not.toBeNull())
  })

  it('load failure → error line + retry, NEVER a blank editor (failed ≠ empty)', async () => {
    const getClaudeMd = vi.fn().mockRejectedValue(new Error('boom'))
    const { container } = render(<AgentSettingsPanel {...mkProps({ getClaudeMd })} />)
    await waitFor(() => expect(q(container, 'agentSettings.persona.loadError')).not.toBeNull())
    expect(q(container, 'agentSettings.persona.input')).toBeNull() // no clobberable buffer
  })

  it('edit → unsaved hint; save → server echo re-syncs, restart hint (saved ≠ active)', async () => {
    const props = mkProps()
    const { container } = render(<AgentSettingsPanel {...props} />)
    const ta = (await waitFor(() => q(container, 'agentSettings.persona.input'))) as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'PERSONA edited' } })
    expect(q(container, 'agentSettings.persona.unsaved')).not.toBeNull()
    fireEvent.click(q(container, 'agentSettings.persona.save') as HTMLButtonElement)
    await waitFor(() => expect(props.updateClaudeMd).toHaveBeenCalledWith('frontend', 'PERSONA edited', 'v1')) // if-match = loaded version
    await waitFor(() => expect(q(container, 'agentSettings.persona.restart')).not.toBeNull())
  })

  it('409 stale → conflict dialog (no silent clobber); reload discards local; overwrite re-fetches + forces', async () => {
    const props = mkProps({ updateClaudeMd: vi.fn().mockRejectedValueOnce(staleError()).mockResolvedValue({ agentId: 'frontend', content: 'MINE', exists: true, version: 'v9' }) })
    const { container } = render(<AgentSettingsPanel {...props} />)
    const ta = (await waitFor(() => q(container, 'agentSettings.persona.input'))) as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'MINE' } })
    fireEvent.click(q(container, 'agentSettings.persona.save') as HTMLButtonElement)
    // the write rejected stale → the dialog opens instead of overwriting
    await waitFor(() => expect(q(container, 'agentSettings.persona.conflict')).not.toBeNull())
    // "overwrite anyway" re-fetches the current version, then forces the write
    const getCalls0 = (props.getClaudeMd as ReturnType<typeof vi.fn>).mock.calls.length
    fireEvent.click(q(container, 'agentSettings.persona.conflict.overwrite') as HTMLButtonElement)
    await waitFor(() => expect((props.getClaudeMd as ReturnType<typeof vi.fn>).mock.calls.length).toBe(getCalls0 + 1))
    await waitFor(() => expect(q(container, 'agentSettings.persona.conflict')).toBeNull())
  })

  it('conflict reload button pulls the live version (discard local) and closes the dialog', async () => {
    const props = mkProps({ updateClaudeMd: vi.fn().mockRejectedValue(staleError()) })
    const { container } = render(<AgentSettingsPanel {...props} />)
    const ta = (await waitFor(() => q(container, 'agentSettings.persona.input'))) as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'MINE' } })
    fireEvent.click(q(container, 'agentSettings.persona.save') as HTMLButtonElement)
    await waitFor(() => expect(q(container, 'agentSettings.persona.conflict')).not.toBeNull())
    const getCalls0 = (props.getClaudeMd as ReturnType<typeof vi.fn>).mock.calls.length
    fireEvent.click(q(container, 'agentSettings.persona.conflict.reload') as HTMLButtonElement)
    await waitFor(() => expect((props.getClaudeMd as ReturnType<typeof vi.fn>).mock.calls.length).toBe(getCalls0 + 1))
    await waitFor(() => expect(q(container, 'agentSettings.persona.conflict')).toBeNull())
  })
})

describe('AgentSettingsPanel — worktree zone', () => {
  it('resolved + path → the path shows', async () => {
    const { container } = render(<AgentSettingsPanel {...mkProps()} />)
    await waitFor(() => expect((q(container, 'agentSettings.worktree.path') as HTMLElement).textContent).toContain('/home/agent/worktrees/frontend'))
  })

  it('resolved + null → the honest "not local" note', async () => {
    const detail: AgentDetail = { id: 'frontend', name: 'Frontend', role: 'WORKER', worktree: 'frontend', launch: 'bash', worktreePath: null }
    const { container } = render(<AgentSettingsPanel {...mkProps({ fetchDetail: vi.fn().mockResolvedValue(detail) })} />)
    await waitFor(() => expect(q(container, 'agentSettings.worktree.notLocal')).not.toBeNull())
  })

  it('load failure → UNRESOLVED → the section hides (never claims "not local" from an unknown)', async () => {
    const fetchDetail = vi.fn().mockRejectedValue(new Error('boom'))
    const { container } = render(<AgentSettingsPanel {...mkProps({ fetchDetail })} />)
    await waitFor(() => expect(fetchDetail).toHaveBeenCalled())
    expect(q(container, 'agentSettings.worktree.notLocal')).toBeNull()
    expect(q(container, 'agentSettings.worktree.path')).toBeNull()
  })
})
