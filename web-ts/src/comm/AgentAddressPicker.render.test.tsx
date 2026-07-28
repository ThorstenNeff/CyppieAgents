// @vitest-environment jsdom
// CYP-876 (OS-D) — render teeth. The addressing control resolves honestly: resolved → jumps to the spoke; unreachable
// → honest hint, no fabricated channel; ambiguous → a visible flag, action disabled, NEVER a silent-first pick.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { AgentAddressPicker } from './AgentAddressPicker'
import type { Channel } from '../types/generated/contract'

afterEach(cleanup)

const ch = (id: string, kind: Channel['kind'], members: string[]): Channel => ({ id, name: id, kind, members })
const spokeFE = ch('po-frontend', 'DIRECT', ['po', 'frontend'])

const pick = (channels: Channel[], onSelectChannel = vi.fn()) => {
  const utils = render(<AgentAddressPicker agentIds={['frontend', 'backend']} channels={channels} onSelectChannel={onSelectChannel} />)
  const select = (agentId: string) => fireEvent.change(utils.getByTestId('agent-address.select'), { target: { value: agentId } })
  return { ...utils, select, onSelectChannel }
}

describe('CYP-876 — AgentAddressPicker (fail-closed recipient addressing)', () => {
  it('★ resolved: picking an agent with one DIRECT spoke enables DM → selects that channel', () => {
    const { select, getByTestId, onSelectChannel } = pick([spokeFE])
    select('frontend')
    const dm = getByTestId('agent-address.dm') as HTMLButtonElement
    expect(dm.disabled).toBe(false)
    fireEvent.click(dm)
    expect(onSelectChannel).toHaveBeenCalledWith('po-frontend')
  })

  it('★ unreachable: no DIRECT spoke → honest "nicht erreichbar", DM disabled, NO channel selected/fabricated', () => {
    const { select, getByTestId, queryByTestId, onSelectChannel } = pick([ch('team', 'GROUP', ['po', 'frontend'])])
    select('frontend')
    expect(getByTestId('agent-address.unreachable')).toBeTruthy()
    expect((getByTestId('agent-address.dm') as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(getByTestId('agent-address.dm'))
    expect(onSelectChannel).not.toHaveBeenCalled()
    expect(queryByTestId('agent-address.ambiguous')).toBeNull()
  })

  it('★ ambiguous: >1 DIRECT spoke → a visible flag, DM disabled, NEVER a silent-first pick', () => {
    // MUT: auto-select spokes[0] on ambiguous → onSelectChannel fires silently → reds.
    const dupA = ch('po-frontend', 'DIRECT', ['po', 'frontend'])
    const dupB = ch('po-frontend-2', 'DIRECT', ['po', 'frontend'])
    const { select, getByTestId, onSelectChannel } = pick([dupA, dupB])
    select('frontend')
    expect(getByTestId('agent-address.ambiguous').getAttribute('role')).toBe('alert')
    expect((getByTestId('agent-address.dm') as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(getByTestId('agent-address.dm'))
    expect(onSelectChannel).not.toHaveBeenCalled() // no silent route
  })
})
