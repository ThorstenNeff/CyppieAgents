import { describe, it, expect } from 'vitest'
import { foldEvents } from './transcriptFolding'
import type { AgentEvent } from './agentEvent'

describe('foldEvents', () => {
  it('concatenates assistant-text deltas sharing an id, keeping the first tsMs and latest complete', () => {
    const rows = foldEvents([
      { kind: 'assistantText', id: 'm', text: 'a', complete: false, tsMs: 1 },
      { kind: 'assistantText', id: 'm', text: 'b', complete: true, tsMs: 2 },
    ])
    expect(rows).toEqual([{ kind: 'assistantText', id: 'm', text: 'ab', complete: true, tsMs: 1 }])
  })

  it('updates a tool call in place (RUNNING → OK), keeping its position and start time', () => {
    const events: AgentEvent[] = [
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'running', tsMs: 1 },
      { kind: 'notice', id: 'n1', text: 'x', tsMs: 2 },
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 9 },
    ]
    expect(foldEvents(events)).toEqual([
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 1 }, // position 0, tsMs 1
      { kind: 'notice', id: 'n1', text: 'x', tsMs: 2 },
    ])
  })

  it('de-duplicates append-only rows by id (reconnect replay is idempotent)', () => {
    const n: AgentEvent = { kind: 'notice', id: 'n1', text: 'once', tsMs: 1 }
    expect(foldEvents([n, n])).toEqual([n])
  })
})
