import { describe, it, expect } from 'vitest'
import { tailSignature } from './AgentTranscript'
import type { AgentEvent } from './agentEvent'

// CYP-404 — tailSignature is the streaming-follow key: it must change both on a discrete tail change (a new row)
// AND when the last assistant row grows IN PLACE (same count, longer text). The second is the streaming case;
// without the `grow` term the pin would stop following a streaming reply. These teeth make dropping it red (M2).
describe('tailSignature (CYP-404)', () => {
  it('changes when the last assistant row grows in place (M2: dropping `grow` would make these equal → red)', () => {
    const short: AgentEvent[] = [{ kind: 'assistantText', id: 'm', text: 'ab', complete: false, tsMs: 1 }]
    const grown: AgentEvent[] = [{ kind: 'assistantText', id: 'm', text: 'abcd', complete: false, tsMs: 1 }]
    expect(tailSignature(short)).not.toBe(tailSignature(grown))
  })

  it('changes on a discrete count change (a new row appended)', () => {
    const one: AgentEvent[] = [{ kind: 'assistantText', id: 'm', text: 'x', complete: true, tsMs: 1 }]
    const two: AgentEvent[] = [...one, { kind: 'notice', id: 'n', text: 'y', tsMs: 2 }]
    expect(tailSignature(one)).not.toBe(tailSignature(two))
  })

  it('is stable for identical content', () => {
    const rows: AgentEvent[] = [{ kind: 'toolCall', id: 't', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 1 }]
    expect(tailSignature(rows)).toBe(tailSignature([...rows]))
  })

  it('is empty for an empty transcript', () => {
    expect(tailSignature([])).toBe('')
  })
})
