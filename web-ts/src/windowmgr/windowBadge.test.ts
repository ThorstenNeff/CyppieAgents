// CYP-646 — the pure Count/Severity window-badge policy. Teeth pin the focus-gating (a focused window shows no
// badge), the ≥WARN severity threshold, the max-severity fold, and the layout-stable count.
import { describe, it, expect } from 'vitest'
import { maxTailSeverity, commCountBadge, eventSeverityBadge, severityRank, formatBadgeCount } from './windowBadge'
import type { Severity } from '../eventlog/eventLog'

const ev = (severity: Severity) => ({ severity })

describe('maxTailSeverity', () => {
  it('is the max across the tail; null for an empty tail', () => {
    expect(maxTailSeverity([])).toBeNull()
    expect(maxTailSeverity([ev('info'), ev('error'), ev('warn')])).toBe('error')
    expect(maxTailSeverity([ev('debug'), ev('info')])).toBe('info')
  })
})

describe('commCountBadge (focus-gated)', () => {
  it('present only when there are unread AND the comm window is NOT focused', () => {
    expect(commCountBadge(3, false)).toEqual({ kind: 'count', count: 3 })
    // RED if a focused comm window ever shows a badge — a focused window has "seen" its activity.
    expect(commCountBadge(3, true)).toBeNull()
    expect(commCountBadge(0, false)).toBeNull()
  })
})

describe('eventSeverityBadge (≥WARN, focus-gated)', () => {
  it('present only at/above WARN and when the event window is NOT focused', () => {
    expect(eventSeverityBadge('warn', false)).toEqual({ kind: 'severity', severity: 'warn' })
    expect(eventSeverityBadge('error', false)).toEqual({ kind: 'severity', severity: 'error' })
    // RED if a below-WARN tail (info/debug) ever badges — a quiet tail adds no chrome.
    expect(eventSeverityBadge('info', false)).toBeNull()
    expect(eventSeverityBadge('debug', false)).toBeNull()
    expect(eventSeverityBadge('error', true)).toBeNull() // focused → no badge
    expect(eventSeverityBadge(null, false)).toBeNull() // empty tail
  })
})

describe('severityRank + formatBadgeCount', () => {
  it('ranks debug<info<warn<error', () => {
    expect(severityRank('debug')).toBeLessThan(severityRank('info'))
    expect(severityRank('info')).toBeLessThan(severityRank('warn'))
    expect(severityRank('warn')).toBeLessThan(severityRank('error'))
  })
  it('count > 9 collapses to "9+" (layout-stable)', () => {
    expect(formatBadgeCount(3)).toBe('3')
    expect(formatBadgeCount(9)).toBe('9')
    expect(formatBadgeCount(10)).toBe('9+')
    expect(formatBadgeCount(999)).toBe('9+')
  })
})
