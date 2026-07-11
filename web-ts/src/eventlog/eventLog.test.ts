import { describe, it, expect } from 'vitest'
import {
  emptyEventLog,
  applyEventsEvent,
  eventRows,
  severityGlyph,
  revokeEventAccess,
  tailView,
  typeGlyph,
  MAX_LIVE_EVENTS,
} from './eventLog'
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

describe('bounded ring (CYP-448 — no silent caps, spec §5.2)', () => {
  it('drops the OLDEST when over MAX_LIVE_EVENTS and counts them into `trimmed` (disclosed, not silent)', () => {
    let s = emptyEventLog
    const total = MAX_LIVE_EVENTS + 5
    for (let i = 1; i <= total; i++) s = applyEventsEvent(s, { type: 'event', event: ev(`e${i}`, i) })
    expect(s.events).toHaveLength(MAX_LIVE_EVENTS)
    expect(s.trimmed).toBe(5) // the 5 overflow drops are surfaced, never hidden
    expect(s.events[0].seq).toBe(6) // oldest 5 (seq 1..5) dropped; the newest kept
    expect(s.events[s.events.length - 1].seq).toBe(total)
  })

  it('does not trim below the cap', () => {
    let s = emptyEventLog
    for (let i = 1; i <= 3; i++) s = applyEventsEvent(s, { type: 'event', event: ev(`e${i}`, i) })
    expect(s.trimmed).toBe(0)
  })
})

describe('tailView — pause freezes the visible tail (CYP-448, spec §3/§5.6)', () => {
  const events = [ev('a', 1), ev('b', 2), ev('c', 3)]

  it('live: shows all events, zero buffered', () => {
    expect(tailView(events, false, null)).toEqual({ visible: events, bufferedCount: 0 })
  })

  it('paused at the tip: freezes what was shown, counts the newer arrivals as buffered', () => {
    // paused after seq 2 → seq 3 arrived while paused
    const v = tailView(events, true, 2)
    expect(v.visible.map((e) => e.id)).toEqual(['a', 'b']) // frozen — c not shown
    expect(v.bufferedCount).toBe(1) // c is buffered, not silently shown
  })
})

describe('typeGlyph — the per-type group glyph, 1:1 from Compose groupGlyph (CYP-448)', () => {
  it('maps each wire-type family to its group glyph', () => {
    expect(typeGlyph('tool.call')).toBe('⚙')
    expect(typeGlyph('context.usage')).toBe('▦')
    expect(typeGlyph('hook.fired')).toBe('⤵')
    expect(typeGlyph('error.model')).toBe('⚠')
    expect(typeGlyph('log.dropped')).toBe('⚠')
    expect(typeGlyph('agent.restarted')).toBe('⏻')
    expect(typeGlyph('comm.sent')).toBe('⇄')
    expect(typeGlyph('stall.escalated')).toBe('☂') // warden family (07/S11)
    expect(typeGlyph('capability.degraded')).toBe('▽')
    expect(typeGlyph('connector.optin')).toBe('⇆')
    expect(typeGlyph('capacity.changed')).toBe('▤')
    expect(typeGlyph('spawn.rejected')).toBe('▤')
  })

  it('an unmapped/newer wire string falls to the UNKNOWN group glyph (never crashes, never a wrong family)', () => {
    expect(typeGlyph('unknown')).toBe('ⓘ')
    expect(typeGlyph('some.future.type')).toBe('ⓘ')
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
