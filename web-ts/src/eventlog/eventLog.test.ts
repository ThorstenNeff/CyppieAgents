import { describe, it, expect } from 'vitest'
import { emptyEventLog, applyEventsEvent, eventRows, severityGlyph, revokeEventAccess } from './eventLog'
import type { EventSurrogate } from '../types/generated/contract'

const ev = (id: string, seq: number, over: Partial<EventSurrogate> = {}): EventSurrogate => ({
  id,
  seq,
  ts: seq,
  agentId: 'backend',
  projectId: 'p',
  type: 'agent.activity',
  severity: 'info',
  ...over,
})

describe('applyEventsEvent (CYP-432 — event-log VM)', () => {
  it('appends events sorted by seq, deduped by id (reconnect replay is idempotent)', () => {
    let s = applyEventsEvent(emptyEventLog, { type: 'event', event: ev('b', 2) })
    s = applyEventsEvent(s, { type: 'event', event: ev('a', 1) })
    s = applyEventsEvent(s, { type: 'event', event: ev('b', 2) }) // replay of b
    expect(s.events.map((e) => e.id)).toEqual(['a', 'b']) // sorted by seq, no dup
  })

  it('Caughtup flips the live flag (idempotent)', () => {
    expect(emptyEventLog.caughtUp).toBe(false)
    const s = applyEventsEvent(emptyEventLog, { type: 'caughtup' })
    expect(s.caughtUp).toBe(true)
    expect(applyEventsEvent(s, { type: 'caughtup' })).toBe(s) // no-op ref
  })
})

describe('eventRows — gap detection ("no silent caps", PRD §3.3)', () => {
  it('inserts an explicit gap row where seq is non-contiguous, never hiding the drop', () => {
    const rows = eventRows([ev('a', 1), ev('b', 2), ev('e', 5)]) // 3 and 4 dropped
    expect(rows).toEqual([
      { kind: 'event', event: ev('a', 1) },
      { kind: 'event', event: ev('b', 2) },
      { kind: 'gap', afterSeq: 2, count: 2 },
      { kind: 'event', event: ev('e', 5) },
    ])
  })

  it('no gap for contiguous seq', () => {
    expect(eventRows([ev('a', 1), ev('b', 2)]).every((r) => r.kind === 'event')).toBe(true)
  })
})

describe('severityGlyph — colour never the sole signal (port of EventVisuals.glyph)', () => {
  it('a distinct glyph per severity', () => {
    expect([severityGlyph('error'), severityGlyph('warn'), severityGlyph('info'), severityGlyph('debug')]).toEqual([
      '⚠',
      '▲',
      'ⓘ',
      '·',
    ])
  })
})

describe('revokeEventAccess (CYP-432 — fail-closed on WS 1008)', () => {
  it('drops buffered events (no stale bodies) and flags revoked', () => {
    const s = applyEventsEvent(applyEventsEvent(emptyEventLog, { type: 'event', event: ev('a', 1) }), { type: 'caughtup' })
    const r = revokeEventAccess()
    expect(r.events).toEqual([])
    expect(r.caughtUp).toBe(false)
    expect(r.accessRevoked).toBe(true)
    expect(s.events).toHaveLength(1) // original untouched (pure)
  })
})
