import { describe, it, expect } from 'vitest'
import {
  EMPTY_FILTER,
  isFilterActive,
  isCrossProjectView,
  buildEventsQuery,
  cycleAxis,
  canShowRun,
  canShowSession,
  runDrilldown,
  sessionDrilldown,
  drilldownFilter,
  appendPage,
} from './eventBrowse'
import type { EventSurrogate } from '../types/generated/contract'

const ev = (id: string, seq: number, over: Partial<EventSurrogate> = {}): EventSurrogate => ({
  id,
  seq,
  ts: seq,
  agentId: 'backend',
  projectId: 'p',
  type: 'tool.call',
  severity: 'info',
  ...over,
})

describe('isFilterActive / isCrossProjectView (CYP-452 — absence ≠ all-clear)', () => {
  it('default filter is not active', () => {
    expect(isFilterActive(EMPTY_FILTER)).toBe(false)
    expect(isCrossProjectView(EMPTY_FILTER)).toBe(false)
  })
  it('any axis set → active', () => {
    expect(isFilterActive({ ...EMPTY_FILTER, severity: 'error' })).toBe(true)
    expect(isFilterActive({ ...EMPTY_FILTER, agentId: 'backend' })).toBe(true)
  })
  it('a non-null projectId → cross-project view', () => {
    expect(isCrossProjectView({ ...EMPTY_FILTER, projectId: 'all' })).toBe(true)
    expect(isFilterActive({ ...EMPTY_FILTER, projectId: 'all' })).toBe(true)
  })
})

describe('buildEventsQuery (CYP-452 — server-side query, only set axes)', () => {
  it('empty filter → just the limit', () => {
    expect(buildEventsQuery(EMPTY_FILTER, null, 100)).toBe('?limit=100')
  })
  it('sends only non-null axes + afterSeq', () => {
    const q = buildEventsQuery({ ...EMPTY_FILTER, severity: 'warn', agentId: 'be' }, 42, 50)
    expect(q).toContain('severity=warn')
    expect(q).toContain('agentId=be')
    expect(q).toContain('afterSeq=42')
    expect(q).toContain('limit=50')
    expect(q).not.toContain('projectId') // never send an unset axis
  })
  it('sends projectId only when set', () => {
    expect(buildEventsQuery({ ...EMPTY_FILTER, projectId: 'proj9' }, null, 100)).toContain('projectId=proj9')
  })
})

describe('cycleAxis (CYP-452 — chip cycles null → opts → null)', () => {
  it('null → first → second → … → null', () => {
    const opts = ['a', 'b', 'c']
    expect(cycleAxis<string>(null, opts)).toBe('a')
    expect(cycleAxis('a', opts)).toBe('b')
    expect(cycleAxis('c', opts)).toBe(null) // last wraps back to unfiltered
    expect(cycleAxis('unknown', opts)).toBe(null)
  })
})

describe('drilldown honesty — no invented correlation (CYP-452 §5/§6, sharpest tooth)', () => {
  it('showRun only when correlationId present; showSession only when sessionId present', () => {
    const plain = ev('a', 1)
    expect(canShowRun(plain)).toBe(false)
    expect(canShowSession(plain)).toBe(false)
    const withRun = ev('b', 2, { correlationId: 'corr1' })
    expect(canShowRun(withRun)).toBe(true)
    expect(canShowSession(withRun)).toBe(false) // NOT conflated onto the run axis
    const withSession = ev('c', 3, { sessionId: 'sess1' })
    expect(canShowSession(withSession)).toBe(true)
    expect(canShowRun(withSession)).toBe(false)
  })
  it('empty-string field is treated as absent (never a guessed drilldown)', () => {
    expect(canShowRun(ev('d', 4, { correlationId: '' }))).toBe(false)
    expect(runDrilldown(ev('d', 4, { correlationId: '' }))).toBe(null)
  })
  it('drilldownFilter re-queries exactly one axis, never both', () => {
    const run = runDrilldown(ev('e', 5, { correlationId: 'corr9' }))!
    expect(drilldownFilter(run)).toMatchObject({ correlationId: 'corr9', sessionId: null })
    const sess = sessionDrilldown(ev('f', 6, { sessionId: 'sess9' }))!
    expect(drilldownFilter(sess)).toMatchObject({ sessionId: 'sess9', correlationId: null })
  })
})

describe('appendPage (CYP-452 — dedup by id, seq order)', () => {
  it('merges a page, dropping id-dups, keeping ascending seq', () => {
    const merged = appendPage([ev('a', 1), ev('b', 2)], [ev('b', 2), ev('c', 3)])
    expect(merged.map((e) => e.id)).toEqual(['a', 'b', 'c'])
  })
})
