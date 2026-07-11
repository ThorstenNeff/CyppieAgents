// CYP-401 (W3) — the BEHAVIORAL GOLDEN CORPUS: wire events -> expected AgentEvent rows. This is the AC that pins
// the TS mapper's behavior against the Kotlin StreamJsonMapper (the cases mirror the :app:shared mapper tests).
// When Backend2's real export lands, the types regenerate; these behavior assertions stay the source of truth.
import { describe, it, expect } from 'vitest'
import { StreamJsonMapper } from './streamJsonMapper'
import { foldEvents } from './transcriptFolding'
import type { StreamJsonEvent } from '../types/generated/contract'
import type { AgentEvent } from './agentEvent'

const map = (m: StreamJsonMapper, e: StreamJsonEvent, tsMs: number): AgentEvent[] => m.map(e, tsMs)

describe('StreamJsonMapper — system / ready notice (CYP-383)', () => {
  it('fires the ready notice once per session, with the model suffix when present', () => {
    const m = new StreamJsonMapper('Agent ready')
    expect(map(m, { type: 'system', session_id: 's1', uuid: 'u1', model: 'sonnet' }, 100)).toEqual([
      { kind: 'notice', id: 'u1', text: 'Agent ready · sonnet', tsMs: 100 },
    ])
    // same session, later event (e.g. a status compaction) → no re-fire
    expect(map(m, { type: 'system', session_id: 's1', uuid: 'u2' }, 200)).toEqual([])
    // new session → fires again; no model → no suffix
    expect(map(m, { type: 'system', session_id: 's2', uuid: 'u3' }, 300)).toEqual([
      { kind: 'notice', id: 'u3', text: 'Agent ready', tsMs: 300 },
    ])
  })

  it('does not fire for an absent or blank session_id', () => {
    const m = new StreamJsonMapper('ready')
    expect(map(m, { type: 'system', uuid: 'u' }, 1)).toEqual([])
    expect(map(m, { type: 'system', session_id: '', uuid: 'u' }, 1)).toEqual([])
  })
})

describe('StreamJsonMapper — rate_limit / assistant', () => {
  it('drops rate_limit events (never in the transcript)', () => {
    expect(map(new StreamJsonMapper('r'), { type: 'rate_limit_event', session_id: 's' }, 1)).toEqual([])
  })

  it('assistant text: complete iff stop_reason is set; id = message.id ?? uuid', () => {
    const m = new StreamJsonMapper('r')
    expect(
      map(m, { type: 'assistant', uuid: 'a', message: { id: 'm1', content: [{ type: 'text', text: 'hi' }], stop_reason: 'end_turn' } }, 5),
    ).toEqual([{ kind: 'assistantText', id: 'm1', text: 'hi', complete: true, tsMs: 5 }])
    expect(map(m, { type: 'assistant', uuid: 'a2', message: { content: [{ type: 'text', text: 'x' }] } }, 6)).toEqual([
      { kind: 'assistantText', id: 'a2', text: 'x', complete: false, tsMs: 6 },
    ])
  })

  it('drops thinking blocks', () => {
    expect(
      map(new StreamJsonMapper('r'), { type: 'assistant', uuid: 'a', message: { content: [{ type: 'thinking', thinking: '...' }] } }, 1),
    ).toEqual([])
  })

  it('tool_use → RUNNING ToolCall, summary from the preferred key', () => {
    expect(
      map(new StreamJsonMapper('r'), {
        type: 'assistant',
        uuid: 'a',
        message: { content: [{ type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'ls -la', extra: 'x' } }] },
      }, 7),
    ).toEqual([{ kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls -la', status: 'running', tsMs: 7 }])
  })
})

describe('StreamJsonMapper — tool_result resolves the prior ToolCall in place (stateful, CYP-335)', () => {
  it('carries the tool START time forward and emits a Result row dated by arrival', () => {
    const m = new StreamJsonMapper('r')
    const a = map(m, { type: 'assistant', uuid: 'a', message: { content: [{ type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'ls' } }] } }, 10)
    const u = map(m, { type: 'user', uuid: 'u', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: 'ok output', is_error: false }] } }, 20)
    expect(u).toEqual([
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 10 }, // start time kept
      { kind: 'result', id: 'result-t1', label: 'ok output', isError: false, tsMs: 20 },
    ])
    // Folded, the in-place update keeps one tool row (its start time) + the result row.
    expect(foldEvents([...a, ...u])).toEqual([
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 10 },
      { kind: 'result', id: 'result-t1', label: 'ok output', isError: false, tsMs: 20 },
    ])
  })

  it('marks the ToolCall and Result as error on is_error', () => {
    const m = new StreamJsonMapper('r')
    map(m, { type: 'assistant', uuid: 'a', message: { content: [{ type: 'tool_use', id: 't9', name: 'Bash', input: {} }] } }, 1)
    const u = map(m, { type: 'user', uuid: 'u', message: { content: [{ type: 'tool_result', tool_use_id: 't9', content: 'boom', is_error: true }] } }, 2)
    expect(u[0]).toMatchObject({ kind: 'toolCall', id: 't9', status: 'error' })
    expect(u[1]).toMatchObject({ kind: 'result', id: 'result-t9', isError: true, label: 'boom' })
  })
})

describe('StreamJsonMapper — user text / result', () => {
  it('surfaces a platform-injected message as IncomingSystem; drops a plain user echo', () => {
    const m = new StreamJsonMapper('r')
    expect(
      map(m, { type: 'user', uuid: 'u', injected_source: 'compact-orchestrator', message: { content: [{ type: 'text', text: 'do compact' }] } }, 5),
    ).toEqual([{ kind: 'incomingSystem', id: 'u', text: 'do compact', tsMs: 5 }])
    expect(map(m, { type: 'user', uuid: 'u2', message: { content: [{ type: 'text', text: 'echo' }] } }, 6)).toEqual([])
  })

  it('result: success is silent; an error becomes a Turn-Fehler notice with the subtype', () => {
    const m = new StreamJsonMapper('r')
    expect(map(m, { type: 'result', uuid: 'r1', subtype: 'success', is_error: false }, 9)).toEqual([])
    expect(map(m, { type: 'result', uuid: 'r2', is_error: false }, 9)).toEqual([]) // subtype null = success
    expect(map(m, { type: 'result', uuid: 'r3', subtype: 'error_max_turns', is_error: true }, 9)).toEqual([
      { kind: 'notice', id: 'r3', text: 'Turn-Fehler: error_max_turns', tsMs: 9 },
    ])
  })
})

describe('StreamJsonMapper — helpers', () => {
  it('falls back to a per-mapper sequence id when the wire id is absent', () => {
    const m = new StreamJsonMapper('r')
    expect(map(m, { type: 'result', subtype: 'x', is_error: true }, 1)[0].id).toBe('ev-0')
    expect(map(m, { type: 'result', subtype: 'y', is_error: true }, 2)[0].id).toBe('ev-1')
  })

  it('summary: first key when no preferred key; truncates to 80 with an ellipsis', () => {
    const m = new StreamJsonMapper('r')
    const firstKey = map(m, { type: 'assistant', uuid: 'a', message: { content: [{ type: 'tool_use', id: 't', name: 'X', input: { foo: 'bar' } }] } }, 1)
    expect((firstKey[0] as { summary: string }).summary).toBe('bar')

    const long = 'x'.repeat(200)
    const m2 = new StreamJsonMapper('r')
    const trunc = map(m2, { type: 'assistant', uuid: 'a', message: { content: [{ type: 'tool_use', id: 't', name: 'X', input: { command: long } }] } }, 1)
    const s = (trunc[0] as { summary: string }).summary
    expect(s.length).toBe(80)
    expect(s.endsWith('…')).toBe(true)
  })
})
