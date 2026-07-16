// CYP-657 — teeth for the Per-Agent-Settings honesty core: the contrast guard (invalid fail-closed, distinguishable
// floor, min-fold over both themes), the CLAUDE.md conflict policy (nullable if-match no-silent-clobber, stale→dialog,
// hint precedence), and the worktree failed≠remote zone.
import { describe, it, expect } from 'vitest'
import { RestError } from '../net/rest'
import {
  COLOR_SWATCHES,
  parseHexColor,
  normalizeHex,
  contrastRatio,
  readableAccentOn,
  evaluateColor,
  AA_TEXT_CONTRAST,
  DISTINGUISHABLE_FLOOR,
  SURFACE_LIGHT,
  expectedVersion,
  isClaudeMdDirty,
  classifyWriteError,
  baselineFromView,
  claudeMdHint,
  worktreeZone,
  CLAUDE_MD_STALE_CODE,
} from './agentSettingsModel'

const restError = (status: number, code: string) =>
  new RestError(status, 'POST', '/api/agents/x/claude-md', JSON.stringify({ error: { code, message: code } }))

describe('parseHexColor', () => {
  it('accepts #RGB / #RRGGBB (# optional, any case); rejects the rest', () => {
    expect(parseHexColor('#4488CC')).toEqual({ r: 0x44, g: 0x88, b: 0xcc })
    expect(parseHexColor('4488cc')).toEqual({ r: 0x44, g: 0x88, b: 0xcc })
    expect(parseHexColor('#abc')).toEqual({ r: 0xaa, g: 0xbb, b: 0xcc })
    expect(parseHexColor('  #FFF  ')).toEqual({ r: 255, g: 255, b: 255 })
    for (const bad of ['', '#12', '#1234', 'blue', '#GG0011', '#4488CCX', 'rgb(1,2,3)']) {
      expect(parseHexColor(bad), bad).toBeNull()
    }
  })
  it('normalizeHex canonicalises to lower-case #rrggbb (stable swatch equality/storage)', () => {
    expect(normalizeHex('#ABC')).toBe('#aabbcc')
    expect(normalizeHex('4488CC')).toBe('#4488cc')
    expect(normalizeHex('nope')).toBeNull()
  })
})

describe('contrastRatio', () => {
  it('is 21:1 for black/white and order-independent', () => {
    const black = { r: 0, g: 0, b: 0 }
    const white = { r: 255, g: 255, b: 255 }
    expect(contrastRatio(black, white)).toBeCloseTo(21, 5)
    expect(contrastRatio(white, black)).toBeCloseTo(21, 5)
    expect(contrastRatio(white, white)).toBeCloseTo(1, 5)
  })
})

describe('readableAccentOn (preview adaptation)', () => {
  it('leaves an already-readable colour untouched (moved 0)', () => {
    // black on white already clears AA → no move.
    const r = readableAccentOn('#000000', SURFACE_LIGHT)
    expect(r).toEqual({ hex: '#000000', moved: 0 })
  })
  it('nudges a raw pastel to clear the AA text floor on the light surface', () => {
    const raw = '#5FD0BE' // ~1.87:1 as text on white — below AA
    const adapted = readableAccentOn(raw, SURFACE_LIGHT)!
    expect(adapted.moved).toBeGreaterThan(0)
    // the adapted colour actually clears the floor it was adapted for (the point of the port)
    const c = contrastRatio(parseHexColor(adapted.hex)!, parseHexColor(SURFACE_LIGHT)!)
    expect(c).toBeGreaterThanOrEqual(AA_TEXT_CONTRAST)
  })
  it('returns null for an invalid raw hex (never a guessed colour)', () => {
    expect(readableAccentOn('nope', SURFACE_LIGHT)).toBeNull()
  })
})

describe('evaluateColor (contrast guard)', () => {
  it('an invalid hex is invalid — the fail-closed, non-persistable verdict', () => {
    expect(evaluateColor('nope')).toEqual({ kind: 'invalid' })
    expect(evaluateColor('#12')).toEqual({ kind: 'invalid' })
  })
  it('every CYP-14 swatch is ok — a curated palette must not nag', () => {
    for (const sw of COLOR_SWATCHES) {
      expect(evaluateColor(sw), sw).toEqual({ kind: 'ok' })
    }
  })
  it('a near-white pick is degraded on the LIGHT theme (melts into the surface)', () => {
    const v = evaluateColor('#F2F2F2')
    expect(v.kind).toBe('degraded')
    if (v.kind === 'degraded') {
      expect(v.worstTheme).toBe('light')
      expect(v.ratio).toBeLessThan(DISTINGUISHABLE_FLOOR)
    }
  })
  it('a near-surface-dark pick is degraded on the DARK theme (min-fold catches the other-theme miss)', () => {
    const v = evaluateColor('#0A1620') // near #06121A — readable on light, invisible on dark
    expect(v.kind).toBe('degraded')
    if (v.kind === 'degraded') expect(v.worstTheme).toBe('dark')
  })
  it('a strong mid colour is ok on both themes', () => {
    expect(evaluateColor('#4488CC')).toEqual({ kind: 'ok' })
  })
})

describe('expectedVersion (no-silent-clobber if-match)', () => {
  it('sends the loaded version when the file exists, null when it does not (expect-absent first write)', () => {
    expect(expectedVersion(true, 'v7')).toBe('v7')
    // RED if this ever narrows null→"" : an "" if-match on an absent file re-breaks the first write with a 409 loop.
    expect(expectedVersion(false, null)).toBeNull()
    expect(expectedVersion(false, 'stale-ghost')).toBeNull() // !exists wins — no if-match at all
    expect(expectedVersion(true, null)).toBeNull() // exists but versionless → null passes through, not ""
  })
})

describe('classifyWriteError (stale → dialog, else save error)', () => {
  it('maps only the stale code to a conflict; every other reject is a plain error', () => {
    expect(classifyWriteError(restError(409, CLAUDE_MD_STALE_CODE))).toBe('stale')
    expect(classifyWriteError(restError(409, 'something_else'))).toBe('error')
    expect(classifyWriteError(restError(500, 'server_error'))).toBe('error')
    expect(classifyWriteError(new Error('network'))).toBe('error')
    expect(classifyWriteError(null)).toBe('error')
  })
})

describe('isClaudeMdDirty + baselineFromView', () => {
  it('dirty iff the buffer diverges from the baseline', () => {
    expect(isClaudeMdDirty('a', 'a')).toBe(false)
    expect(isClaudeMdDirty('a ', 'a')).toBe(true)
  })
  it('re-syncs the baseline to the server echo (dirty clears, fresh version carried)', () => {
    const b = baselineFromView({ agentId: 'x', content: 'NEW', exists: true, version: 'v9' })
    expect(b).toEqual({ baseline: 'NEW', version: 'v9', exists: true })
    expect(isClaudeMdDirty('NEW', b.baseline)).toBe(false)
    // an absent-version echo stays null (not "")
    expect(baselineFromView({ agentId: 'x', content: '', exists: false }).version).toBeNull()
  })
})

describe('claudeMdHint (single disclosure, precedence)', () => {
  it('saveError > unsaved > restart > empty', () => {
    // a failed save must never be masked by a stale "saved, restart" — RED if the order flips.
    expect(claudeMdHint({ saveError: true, dirty: true, saved: true, empty: false })).toBe('saveError')
    expect(claudeMdHint({ saveError: false, dirty: true, saved: true, empty: false })).toBe('unsaved')
    expect(claudeMdHint({ saveError: false, dirty: false, saved: true, empty: false })).toBe('restart')
    expect(claudeMdHint({ saveError: false, dirty: false, saved: false, empty: true })).toBe('empty')
    expect(claudeMdHint({ saveError: false, dirty: false, saved: false, empty: false })).toBeNull()
  })
})

describe('worktreeZone (failed ≠ remote)', () => {
  it('unresolved → hidden (never claims "not local" from an unknown); resolved → path|notLocal', () => {
    // RED if an unresolved/failed load ever renders "not local" — that would assert a fact it does not have.
    expect(worktreeZone(false, null)).toBe('hidden')
    expect(worktreeZone(false, '/somewhere')).toBe('hidden')
    expect(worktreeZone(true, '/home/agent/worktree')).toBe('path')
    expect(worktreeZone(true, null)).toBe('notLocal')
    expect(worktreeZone(true, undefined)).toBe('notLocal')
    expect(worktreeZone(true, '')).toBe('notLocal') // empty path ≠ a real path
  })
})
