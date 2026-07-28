// @vitest-environment jsdom
// CYP-890 (NR-3) — the read-only-composer WIRING tooth. A PRODUCT_LEAD is a read-only reviewer: the server enforces
// canWrite=false (postAsAgent 403s their send); the client mirrors that truth by disabling the composer + showing a
// reason hint (render-mirrors-authority, NOT the gate itself). A WORKER/PO composer stays enabled.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { AgentWindow, type AgentWindowProps } from '../AgentWindow'
import { useHubStore } from '../state/hubStore'
import { resetAgentVms } from './agentVmStore'
import { FakeSocketHub } from '../net/testing/fakeSocket'
import type { Agent } from '../types/generated/contract'

const agent = (id: string, role: Agent['role']): Agent => ({ id, name: id, role, worktree: id })

afterEach(() => {
  cleanup()
  resetAgentVms()
})

const props = (hub: FakeSocketHub, agentId: string): AgentWindowProps => ({
  agentId,
  wsBase: 'ws://x',
  token: '',
  operator: true,
  terminalState: 'MEDIATED',
  onRequestMode: () => {},
  lifecycleState: 'UNKNOWN',
  lifecyclePending: undefined,
  lifecycleError: null,
  lifecycleErrorCode: undefined,
  onLifecycle: () => {},
  socketDeps: { factory: hub.factory, schedule: hub.runNow },
})

describe('CYP-890 — AgentWindow read-only composer for PRODUCT_LEAD', () => {
  it('★ a PRODUCT_LEAD agent → composer disabled + reason hint', () => {
    useHubStore.getState().setRoster([agent('pl', 'PRODUCT_LEAD')])
    const hub = new FakeSocketHub()
    const { getByTestId } = render(<AgentWindow {...props(hub, 'pl')} />)
    expect((getByTestId('composer-input') as HTMLInputElement).disabled).toBe(true)
    expect(getByTestId('composer-readonly-hint')).toBeTruthy()
  })

  it('★ a WORKER agent → composer enabled, no read-only hint', () => {
    // MUT: gate on the wrong role / always-disabled → this reds.
    useHubStore.getState().setRoster([agent('w1', 'WORKER')])
    const hub = new FakeSocketHub()
    const { getByTestId, queryByTestId } = render(<AgentWindow {...props(hub, 'w1')} />)
    expect((getByTestId('composer-input') as HTMLInputElement).disabled).toBe(false)
    expect(queryByTestId('composer-readonly-hint')).toBeNull()
  })
})
