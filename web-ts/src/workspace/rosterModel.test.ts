// CYP-650 — the pure roster/audit policy. Teeth pin the privacy rule (short non-identifying label; displayName wins
// when present, else an 8-char id prefix — never the full identity) + the tier text mapping.
import { describe, it, expect } from 'vitest'
import { memberLabel, shortId, tierLabel, auditLine, formatAuditTime } from './rosterModel'
import type { WorkspaceMember, OperatorAudit } from '../types/generated/contract'

const member = (o: Partial<WorkspaceMember>): WorkspaceMember => ({ identityId: 'abcdefgh1234567890', tier: 'MEMBER', ...o })

describe('memberLabel (short, non-identifying)', () => {
  it('uses displayName when the server supplies one', () => {
    expect(memberLabel(member({ displayName: 'Alex' }))).toBe('Alex')
  })
  it('falls back to the 8-char id prefix (never the full identity) when displayName is null/blank', () => {
    // RED if the full identityId ever leaks — the label must be the short prefix.
    expect(memberLabel(member({ identityId: 'abcdefgh1234567890', displayName: null }))).toBe('abcdefgh')
    expect(memberLabel(member({ displayName: '   ' }))).toBe('abcdefgh')
  })
})

describe('shortId', () => {
  it('is exactly the first 8 chars', () => {
    expect(shortId('abcdefgh1234567890')).toBe('abcdefgh')
    expect(shortId('short')).toBe('short')
  })
})

describe('tierLabel (text, never colour-alone)', () => {
  it('OPERATOR (any case) → Operator; everything else → Mitglied', () => {
    expect(tierLabel('OPERATOR')).toBe('Operator')
    expect(tierLabel('operator')).toBe('Operator')
    expect(tierLabel('MEMBER')).toBe('Mitglied')
    expect(tierLabel('whatever')).toBe('Mitglied')
  })
})

describe('auditLine + formatAuditTime', () => {
  const a: OperatorAudit = { actor: 'op', method: 'POST', path: '/api/agents/backend/start', tsMs: 1_700_000_000_000 }
  it('auditLine is the verb + path (content-free)', () => {
    expect(auditLine(a)).toBe('POST /api/agents/backend/start')
  })
  it('formatAuditTime is a 24h HH:MM:SS clock', () => {
    expect(formatAuditTime(a.tsMs)).toMatch(/^\d{2}:\d{2}:\d{2}$/)
  })
})
