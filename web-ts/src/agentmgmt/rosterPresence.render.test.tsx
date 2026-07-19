// @vitest-environment jsdom
// CYP-741 — teeth for the roster presence dot + busy marker (UIUX2 spec 37e7a22c §3/§4).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { AgentManagementPanel } from './AgentManagementPanel'
import { LifecycleHeader } from '../agentview/LifecycleHeader'
import type { Agent } from '../types/generated/contract'
import type { AgentRunState } from '../state/hubReducers'

const AGENTS: Agent[] = [{ id: 'po', name: 'PO', role: 'PO', worktree: 'po' }]

const roster = (runState?: AgentRunState, busy?: boolean) =>
  render(
    <AgentManagementPanel
      agents={AGENTS}
      operator={true}
      runStateByAgent={new Map(runState === undefined ? [] : [['po', runState]])}
      busyByAgent={new Map(busy === undefined ? [] : [['po', busy]])}
      onCreate={vi.fn()}
      onUpdate={vi.fn()}
      onRemove={vi.fn()}
      fetchDetail={vi.fn()}
      getConnectors={vi.fn()}
      onSetConnector={vi.fn()}
    />,
  )

beforeEach(cleanup)

describe('CYP-741 — presence in the roster reads the same as in the header', () => {
  it('a RUNNING agent gets a filled dot (non-vacuous control)', () => {
    expect(roster('RUNNING').getByTestId('agentMgmt.item.po.dot').dataset.shape).toBe('fill')
  })

  it('★ ③.1 an UNOBSERVED agent is a RING, not STOPPED\'s filled disc — unobserved is not off', () => {
    // No lifecycle event has arrived. Painting it like STOPPED would claim we looked and found it stopped.
    expect(roster(undefined).getByTestId('agentMgmt.item.po.dot').dataset.shape).toBe('ring')
    cleanup() // the two renders would otherwise both be in the document
    expect(roster('STOPPED').getByTestId('agentMgmt.item.po.dot').dataset.shape).toBe('fill') // the contrast
  })

  it('★ ④ CONSISTENCY: the roster dot and the header dot agree for every state — one fact, one source', () => {
    // The point of the ticket: not a new dot, the SAME dot in a second place. If either drifts, this reds.
    // UNKNOWN is expressed as ABSENCE on both sides (no lifecycle event / no state prop) — which is precisely the
    // state whose rendering must agree: the roster's `?? 'UNKNOWN'` default and the header's own default.
    for (const state of ['RUNNING', 'STOPPED', 'ERROR', undefined] as const) {
      cleanup()
      const r = roster(state).getByTestId('agentMgmt.item.po.dot')
      const shape = r.dataset.shape
      const role = r.dataset.role
      cleanup()
      const h = render(
        <LifecycleHeader
          agentId="po"
          state={state ?? 'UNKNOWN'}
          pending={undefined}
          operator={true}
          onStart={vi.fn()}
          onStop={vi.fn()}
          onRestart={vi.fn()}
        />,
      ).getByTestId('lifecycle.dot.po')
      expect({ state, shape, role }).toEqual({ state, shape: h.dataset.shape, role: h.dataset.role })
    }
  })

  it('★ ③.2 busy is fail-closed — an absent value never lights the marker', () => {
    expect(roster('RUNNING', undefined).queryByTestId('agentMgmt.item.po.busy')).toBeNull()
    cleanup()
    expect(roster('RUNNING', false).queryByTestId('agentMgmt.item.po.busy')).toBeNull()
    cleanup()
    expect(roster('RUNNING', true).getByTestId('agentMgmt.item.po.busy')).toBeTruthy() // and true does light it
  })

  it('★ ③.3 busy is suppressed under ERROR — "working" beside an error is a mixed signal', () => {
    const { queryByTestId, getByTestId } = roster('ERROR', true)
    expect(queryByTestId('agentMgmt.item.po.busy')).toBeNull()
    expect(getByTestId('agentMgmt.item.po.dot').dataset.role).toBe('error') // the error still shows
  })

  it('★ ③.5 colour is never the sole carrier — the text label stays and the dot is aria-hidden', () => {
    const { getByTestId } = roster('RUNNING')
    expect(getByTestId('agentMgmt.item.po.status').textContent).toBeTruthy() // meaning lives in the words
    expect(getByTestId('agentMgmt.item.po.dot').getAttribute('aria-hidden')).toBe('true')
  })

  it('★ busy stays DISTINCT from run-state — a RUNNING agent may be idle', () => {
    const idle = roster('RUNNING', false)
    expect(idle.getByTestId('agentMgmt.item.po.dot').dataset.shape).toBe('fill') // running
    expect(idle.queryByTestId('agentMgmt.item.po.busy')).toBeNull() // …and not busy
  })
})
