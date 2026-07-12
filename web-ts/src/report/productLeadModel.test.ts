import { describe, it, expect } from 'vitest'
import { reportTypeLabel, asOfLabel, provenanceText, REPORT_TYPES } from './productLeadModel'
import type { ReportSnapshot } from '../types/generated/contract'

const snap = (over: Partial<ReportSnapshot> = {}): ReportSnapshot => ({
  id: 'r1',
  type: 'defects',
  generatedAt: 1_700_000_000_000,
  projectId: 'p',
  sources: ['events', 'comm'],
  window: { sinceLabel: 'Mo', untilLabel: 'Di' },
  sections: [],
  ...over,
})

describe('report type labels (CYP-464)', () => {
  it('the three types', () => {
    expect(REPORT_TYPES).toEqual(['usage', 'status', 'defects'])
    expect([reportTypeLabel('usage'), reportTypeLabel('status'), reportTypeLabel('defects')]).toEqual([
      'Nutzungs-Leitfaden',
      'Status-Report',
      'Defekt-/Lücken-Register',
    ])
  })
})

describe('asOfLabel — snapshot ≠ live (CYP-464 §0)', () => {
  it('prefixes "Stand:" so a snapshot is never read as the current state', () => {
    expect(asOfLabel(1_700_000_000_000)).toMatch(/^Stand: /)
  })
})

describe('provenanceText — named sources + window, never invented completeness (CYP-464 §6)', () => {
  it('lists the sources and the observation window', () => {
    const p = provenanceText(snap())
    expect(p).toContain('events')
    expect(p).toContain('comm')
    expect(p).toContain('Mo')
    expect(p).toContain('Di')
    expect(p).toContain('Beobachtet') // observed, not authoritative
  })
  it('handles no window / no sources honestly (—, no window clause)', () => {
    const p = provenanceText(snap({ sources: [], window: {} }))
    expect(p).toContain('—')
    expect(p).not.toContain('Fenster')
  })
})
