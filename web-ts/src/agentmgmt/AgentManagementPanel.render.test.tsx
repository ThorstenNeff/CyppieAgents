// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act } from '@testing-library/react'
import { AgentManagementPanel, type AgentManagementPanelProps } from './AgentManagementPanel'
import { RestError } from '../net/rest'
import type { Agent } from '../types/generated/contract'
import type { AgentRunState } from '../state/hubReducers'

const roster: Agent[] = [
  { id: 'po', name: 'PO', role: 'PO', worktree: 'po' },
  { id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend' },
]

const rejectWith = (code: string) =>
  new RestError(409, 'POST', '/api/agents', JSON.stringify({ error: { code, message: 'x' } }))

const renderPanel = (over: Partial<AgentManagementPanelProps> = {}) => {
  const props: AgentManagementPanelProps = {
    agents: roster,
    operator: true,
    runStateByAgent: new Map<string, AgentRunState>([['backend', 'STOPPED']]),
    onCreate: vi.fn().mockResolvedValue(undefined),
    onUpdate: vi.fn().mockResolvedValue(undefined),
    onRemove: vi.fn().mockResolvedValue(undefined),
    fetchDetail: vi.fn().mockResolvedValue({ role: 'WORKER', persona: 'be persona', launch: 'bash' }),
    getConnectors: vi.fn().mockResolvedValue({
      connectors: [
        { kind: 'stream_json', capabilities: { structuredUsage: 'available', toolGranularity: 'available', reliableResult: 'available', rateLimitSignal: 'available', coordination: 'available', kind: 'stream_json' } },
        { kind: 'mcp', capabilities: { structuredUsage: 'limited', toolGranularity: 'limited', reliableResult: 'limited', rateLimitSignal: 'unavailable', coordination: 'limited', kind: 'mcp' } },
      ],
      default: 'stream_json',
    }),
    onSetConnector: vi.fn().mockResolvedValue(undefined),
    ...over,
  }
  return { props, ...render(<AgentManagementPanel {...props} />) }
}

afterEach(cleanup)

describe('AgentManagementPanel (CYP-450)', () => {
  it('operator gate is present-but-disabled, not omission — list stays visible (tooth 8)', () => {
    const { getByTestId } = renderPanel({ operator: false })
    expect(getByTestId('agentMgmt.list')).toBeTruthy() // display is ungated
    expect(getByTestId('agentMgmt.item.backend')).toBeTruthy()
    expect((getByTestId('agentMgmt.addButton') as HTMLButtonElement).disabled).toBe(true)
    expect(getByTestId('agentMgmt.addButton').getAttribute('aria-disabled')).toBe('true')
    expect((getByTestId('agentMgmt.item.backend.edit') as HTMLButtonElement).disabled).toBe(true)
    expect((getByTestId('agentMgmt.item.backend.remove') as HTMLButtonElement).disabled).toBe(true)
    expect(getByTestId('agentMgmt.gateHint')).toBeTruthy()
  })

  it('create ≠ start: on success the spawnHint shows ("noch nicht gestartet") and the dialog closes (tooth 1)', async () => {
    const { getByTestId, findByTestId, queryByTestId } = renderPanel()
    fireEvent.click(getByTestId('agentMgmt.addButton'))
    expect(getByTestId('connector.picker')).toBeTruthy() // CYP-461: connector picker wired into the add dialog
    fireEvent.change(getByTestId('agentMgmt.add.id.input'), { target: { value: 'qa' } })
    fireEvent.change(getByTestId('agentMgmt.add.name.input'), { target: { value: 'QA' } })
    await act(async () => {
      fireEvent.click(getByTestId('agentMgmt.add.confirm'))
    })
    expect((await findByTestId('agentMgmt.add.spawnHint')).textContent).toContain('startet noch nicht')
    expect(queryByTestId('agentMgmt.add.dialog')).toBeNull()
  })

  it('a server reject on add surfaces on the error line, dialog stays open (server-authoritative, teeth 9/2)', async () => {
    const onCreate = vi.fn().mockRejectedValue(rejectWith('agent_exists'))
    const { getByTestId, findByTestId } = renderPanel({ onCreate })
    fireEvent.click(getByTestId('agentMgmt.addButton'))
    fireEvent.change(getByTestId('agentMgmt.add.id.input'), { target: { value: 'po' } })
    fireEvent.change(getByTestId('agentMgmt.add.name.input'), { target: { value: 'dup' } })
    await act(async () => {
      fireEvent.click(getByTestId('agentMgmt.add.confirm'))
    })
    expect((await findByTestId('agentMgmt.add.error')).textContent).toContain('existiert bereits')
    expect(getByTestId('agentMgmt.add.dialog')).toBeTruthy() // stays open on reject
  })

  it('remove is an alertdialog with consequences; worktree default = keep; delete reveals the warning (teeth 2/3)', () => {
    const { getByTestId, queryByTestId } = renderPanel()
    fireEvent.click(getByTestId('agentMgmt.item.backend.remove'))
    const dialog = getByTestId('agentMgmt.remove.dialog')
    expect(dialog.getAttribute('role')).toBe('alertdialog')
    expect(getByTestId('agentMgmt.remove.consequences')).toBeTruthy()
    // default = keep (non-destructive preselected), confirm label reflects it, no warning yet
    expect((getByTestId('agentMgmt.remove.worktreeChoice.keep') as HTMLInputElement).checked).toBe(true)
    expect((getByTestId('agentMgmt.remove.worktreeChoice.delete') as HTMLInputElement).checked).toBe(false)
    expect(getByTestId('agentMgmt.remove.confirm').textContent).toBe('Agent entfernen')
    expect(queryByTestId('agentMgmt.remove.worktreeWarning')).toBeNull()
    // choosing delete → data-loss warning + destructive wording
    fireEvent.click(getByTestId('agentMgmt.remove.worktreeChoice.delete'))
    expect(getByTestId('agentMgmt.remove.worktreeWarning').textContent).toContain('unwiderbringlich')
    expect(getByTestId('agentMgmt.remove.confirm').textContent).toBe('Endgültig löschen')
  })

  it('the last-PO remove is a server reject shown on the error line, no faked success (tooth 4)', async () => {
    const onRemove = vi.fn().mockRejectedValue(rejectWith('last_po'))
    const { getByTestId, findByTestId } = renderPanel({ onRemove })
    fireEvent.click(getByTestId('agentMgmt.item.po.remove'))
    await act(async () => {
      fireEvent.click(getByTestId('agentMgmt.remove.confirm'))
    })
    expect((await findByTestId('agentMgmt.remove.error')).textContent).toContain('einzige PO')
    expect(getByTestId('agentMgmt.remove.dialog')).toBeTruthy() // dialog stays; no success
  })

  it('edit locks id+worktree and, on save, shows the AMBER effect-hint with no restart control (teeth 5/6)', async () => {
    const { getByTestId, findByTestId, queryByTestId } = renderPanel()
    await act(async () => {
      fireEvent.click(getByTestId('agentMgmt.item.backend.edit'))
    })
    expect(getByTestId('agentMgmt.edit.idLockedHint')).toBeTruthy()
    expect(queryByTestId('agentMgmt.add.id.input')).toBeNull() // no editable id field anywhere
    // no restart control lives in this surface (reuse P2-a restartBtn instead)
    expect(queryByTestId('agentMgmt.edit.restart')).toBeNull()
    await act(async () => {
      fireEvent.click(getByTestId('agentMgmt.edit.save'))
    })
    const hint = await findByTestId('agentMgmt.edit.effectHint')
    expect(hint.textContent).toContain('neu starten')
    expect(hint.className).toContain('effect-hint') // amber effect-hint styling hook (never success-green)
  })
})

describe('AgentManagementPanel — CYP-288 honest load-error + retry (failed roster ≠ empty)', () => {
  it('failed roster load (empty) → error+retry, NOT the empty list; retry fires', () => {
    const onRetryLoad = vi.fn()
    const { getByTestId, queryByTestId } = renderPanel({ agents: [], loadError: true, onRetryLoad })
    expect(getByTestId('agentMgmt.loadError')).toBeTruthy()
    expect(queryByTestId('agentMgmt.list')).toBeNull() // the empty <ul> ("no agents") is replaced by the honest error
    fireEvent.click(getByTestId('agentMgmt.loadError.retry'))
    expect(onRetryLoad).toHaveBeenCalledTimes(1)
  })

  it('roster present + error flag → list renders, error hidden (live/WS roster wins — flag 4)', () => {
    const { getByTestId, queryByTestId } = renderPanel({ loadError: true })
    expect(getByTestId('agentMgmt.list')).toBeTruthy()
    expect(getByTestId('agentMgmt.item.backend')).toBeTruthy()
    expect(queryByTestId('agentMgmt.loadError')).toBeNull()
  })

  it('genuinely-empty roster (no error) → the empty list, not the error (non-vacuum contrast)', () => {
    const { getByTestId, queryByTestId } = renderPanel({ agents: [], loadError: false })
    expect(getByTestId('agentMgmt.list')).toBeTruthy() // empty <ul> present, honest "no agents"
    expect(queryByTestId('agentMgmt.loadError')).toBeNull()
  })
})
