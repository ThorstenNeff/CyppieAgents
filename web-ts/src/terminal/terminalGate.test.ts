import { describe, it, expect, afterEach } from 'vitest'
import { mayOpenTerminalClient } from './terminalGate'

const g = globalThis as { CYPPIE_OPERATOR_TOKEN?: string }
afterEach(() => {
  delete g.CYPPIE_OPERATOR_TOKEN
})

describe('mayOpenTerminalClient (CYP-405 fail-closed operator gate)', () => {
  it('is false without an operator token (public/MEMBER serve)', () => {
    delete g.CYPPIE_OPERATOR_TOKEN
    expect(mayOpenTerminalClient()).toBe(false)
  })

  it('is true only on the operator serve (token present)', () => {
    g.CYPPIE_OPERATOR_TOKEN = 'op-token'
    expect(mayOpenTerminalClient()).toBe(true)
  })
})
