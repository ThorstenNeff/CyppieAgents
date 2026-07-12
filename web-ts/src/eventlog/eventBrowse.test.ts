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
  isSinglePane,
  PANE_COLLAPSE_WIDTH,
  projectCycleOptions,
  PROJECT_ALL,
  TYPE_CYCLE,
  compactDoneSummary,
  resumeOutcomeSummary,
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

describe('isSinglePane (CYP-467 — collapse below PANE_COLLAPSE_WIDTH, never before measured)', () => {
  it('an unmeasured width (0) stays two-pane — never collapse before we know the size', () => {
    expect(isSinglePane(0)).toBe(false)
  })
  it('narrow (< threshold) collapses; at/above threshold stays two-pane', () => {
    expect(isSinglePane(PANE_COLLAPSE_WIDTH - 1)).toBe(true)
    expect(isSinglePane(PANE_COLLAPSE_WIDTH)).toBe(false)
    expect(isSinglePane(PANE_COLLAPSE_WIDTH + 1)).toBe(false)
  })
})

describe('projectCycleOptions (CYP-467/94 — active omitted, all appended)', () => {
  it('drops the active project (null already = active) and appends the all sentinel last', () => {
    expect(projectCycleOptions(['team-1', 'team-2', 'team-3'], 'team-1')).toEqual(['team-2', 'team-3', PROJECT_ALL])
  })
  it('cycles null(active) → other → all → null via cycleAxis', () => {
    const opts = projectCycleOptions(['team-1', 'team-2'], 'team-1')
    expect(cycleAxis<string>(null, opts)).toBe('team-2')
    expect(cycleAxis<string>('team-2', opts)).toBe(PROJECT_ALL)
    expect(cycleAxis<string>(PROJECT_ALL, opts)).toBeNull()
  })
})

describe('compactDoneSummary (CYP-467/326 — X/N; amber on timeout/abort, neutral on full, never green)', () => {
  const done = (detail: unknown) => ev('d', 1, { type: 'compact.orchestration.done', detail })
  it('a clean full run is neutral (never warn/green)', () => {
    expect(compactDoneSummary(done({ completed: 5, total: 5, pendingAgentIds: [] }))).toEqual({
      text: '5/5 Agenten compactet, 0 Timeout',
      warn: false,
    })
  })
  it('a timeout (pendingAgentIds non-empty) is WARN amber with the count in text', () => {
    const s = compactDoneSummary(done({ completed: 3, total: 5, pendingAgentIds: ['a', 'b'] }))
    expect(s).toEqual({ text: '3/5 Agenten compactet, 2 Timeout', warn: true })
  })
  it('an abort is a DISTINCT label (never "timed out", never success) and WARN amber', () => {
    const s = compactDoneSummary(done({ completed: 2, total: 5, pendingAgentIds: ['a', 'b', 'c'], aborted: true }))
    expect(s?.warn).toBe(true)
    expect(s?.text).toContain('Abgebrochen')
    expect(s?.text).not.toContain('Timeout')
  })
  it('absent when a count is missing, or for a non-orchestration event', () => {
    expect(compactDoneSummary(done({ completed: 5, pendingAgentIds: [] }))).toBeNull()
    expect(compactDoneSummary(done('nope'))).toBeNull()
    expect(compactDoneSummary(ev('t', 1, { type: 'tool.call', detail: { completed: 5, total: 5 } }))).toBeNull()
  })
})

describe('resumeOutcomeSummary (CYP-467/356 — CONTEXT_LOST amber, others neutral, unknown → none)', () => {
  const ro = (detail: unknown) => ev('r', 1, { type: 'resume.outcome', detail })
  it('CONTEXT_LOST is WARN amber', () => {
    expect(resumeOutcomeSummary(ro({ outcome: 'CONTEXT_LOST' }))?.warn).toBe(true)
  })
  it('RESUMED_WITH_CONTEXT and FRESH_NO_RESUME are neutral (not failures, never success)', () => {
    expect(resumeOutcomeSummary(ro({ outcome: 'RESUMED_WITH_CONTEXT' }))?.warn).toBe(false)
    expect(resumeOutcomeSummary(ro({ outcome: 'FRESH_NO_RESUME' }))?.warn).toBe(false)
  })
  it('an unknown/newer outcome (incl. a prototype key) → no fabricated summary', () => {
    expect(resumeOutcomeSummary(ro({ outcome: 'SOMETHING_NEW' }))).toBeNull()
    expect(resumeOutcomeSummary(ro({ outcome: 'toString' }))).toBeNull() // must not leak Object.prototype
    expect(resumeOutcomeSummary(ro({}))).toBeNull()
  })
})

describe('TYPE_CYCLE (CYP-467 — the type axis is a real curated set feeding the server query)', () => {
  it('is a non-empty list of wire strings that cycleAxis can walk', () => {
    expect(TYPE_CYCLE.length).toBeGreaterThan(0)
    expect(cycleAxis<string>(null, TYPE_CYCLE)).toBe(TYPE_CYCLE[0])
    expect(buildEventsQuery({ ...EMPTY_FILTER, type: TYPE_CYCLE[0] }, null, 50)).toContain(`type=${encodeURIComponent(TYPE_CYCLE[0])}`)
  })
})
