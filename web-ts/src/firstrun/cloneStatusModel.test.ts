// CYP-735 §3.3 — teeth for the clone lifecycle (UIUX2 screen-spec 12924c37 §2).
import { describe, it, expect } from 'vitest'
import {
  cloneView,
  clonePollMs,
  isCloneDone,
  isCloneTerminal,
  CLONE_SLOW_MS,
  CLONE_LONG_MS,
  CLONE_POLL_MS,
  CLONE_POLL_THINNED_MS,
} from './cloneStatusModel'
import type { RepoConfigView } from '../types/generated/contract'

const cfg = (over: Partial<RepoConfigView> = {}): RepoConfigView => ({ configured: true, ...over })

describe('CYP-735 §3.3 — the client reports what the server observed, plus only what it can measure', () => {
  it('each server status maps to its own view (non-vacuous control)', () => {
    expect(cloneView(cfg({ cloneStatus: 'NOT_CONFIGURED' }), 0).kind).toBe('notConfigured')
    expect(cloneView(cfg({ cloneStatus: 'CONFIGURED_NEVER_CLONED' }), 0).kind).toBe('configuredNeverCloned')
    expect(cloneView(cfg({ cloneStatus: 'CLONING' }), 0).kind).toBe('cloning')
    expect(cloneView(cfg({ cloneStatus: 'CLONED_OK' }), 0).kind).toBe('ok')
  })

  it('★ ②.1 an absent/null cloneStatus is UNKNOWN, never OK — absence is not reassurance', () => {
    for (const c of [null, cfg(), cfg({ cloneStatus: null }), cfg({ cloneStatus: undefined })]) {
      const v = cloneView(c, 0)
      expect(v.kind).toBe('unknown')
      expect(isCloneDone(v)).toBe(false)
    }
  })

  it('★ an UNRECOGNISED status from a newer server is UNKNOWN, not OK — forward-compat fails closed', () => {
    const v = cloneView(cfg({ cloneStatus: 'SOMETHING_NEW' as never }), 0)
    expect(v.kind).toBe('unknown')
    expect(isCloneDone(v)).toBe(false)
  })

  it('★ ②.2 CONFIGURED_NEVER_CLONED is not done — an accepted URL is not a cloned repo', () => {
    const v = cloneView(cfg({ cloneStatus: 'CONFIGURED_NEVER_CLONED' }), 0)
    expect(isCloneDone(v)).toBe(false)
    expect(isCloneTerminal(v)).toBe(false) // and we keep watching
  })

  it('★ ②.3 slow/long are ATTRIBUTES of cloning — never a separate phase, never a verdict', () => {
    const fresh = cloneView(cfg({ cloneStatus: 'CLONING' }), 0)
    const slow = cloneView(cfg({ cloneStatus: 'CLONING' }), CLONE_SLOW_MS)
    const long = cloneView(cfg({ cloneStatus: 'CLONING' }), CLONE_LONG_MS)
    expect([fresh.kind, slow.kind, long.kind]).toEqual(['cloning', 'cloning', 'cloning']) // same phase throughout
    expect(fresh).toMatchObject({ slow: false, long: false })
    expect(slow).toMatchObject({ slow: true, long: false })
    expect(long).toMatchObject({ slow: true, long: true })
  })

  it('★ ②.3 NO elapsed time turns cloning into failed or ok — a hung clone is indistinguishable from a slow one', () => {
    // The client cannot tell "stuck" from "big repo", so inventing a verdict from the clock would be a fabricated
    // diagnosis. Long-running stays "still running, duration unknown".
    for (const elapsed of [0, CLONE_SLOW_MS, CLONE_LONG_MS, 60 * 60_000, Number.MAX_SAFE_INTEGER]) {
      const v = cloneView(cfg({ cloneStatus: 'CLONING' }), elapsed)
      expect(v.kind).toBe('cloning')
      expect(isCloneTerminal(v)).toBe(false)
      expect(isCloneDone(v)).toBe(false)
    }
  })

  it('★ ②.4 the three failure reasons stay distinct, and an absent reason stays honestly UNKNOWN', () => {
    expect(cloneView(cfg({ cloneStatus: 'CLONE_FAILED', cloneFailReason: 'AUTH' }), 0)).toMatchObject({ reason: 'AUTH' })
    expect(cloneView(cfg({ cloneStatus: 'CLONE_FAILED', cloneFailReason: 'URL_UNREACHABLE' }), 0)).toMatchObject({
      reason: 'URL_UNREACHABLE',
    })
    // never guessed into AUTH/URL — that would send the operator to fix the wrong thing
    expect(cloneView(cfg({ cloneStatus: 'CLONE_FAILED' }), 0)).toMatchObject({ reason: 'UNKNOWN' })
    expect(cloneView(cfg({ cloneStatus: 'CLONE_FAILED', cloneFailReason: null }), 0)).toMatchObject({ reason: 'UNKNOWN' })
  })

  it('★ ③ polling thins but never stops before a verdict — thinning is not giving up', () => {
    expect(clonePollMs(cloneView(cfg({ cloneStatus: 'CLONING' }), 0))).toBe(CLONE_POLL_MS)
    expect(clonePollMs(cloneView(cfg({ cloneStatus: 'CLONING' }), CLONE_LONG_MS))).toBe(CLONE_POLL_THINNED_MS)
    expect(clonePollMs(cloneView(cfg({ cloneStatus: 'CLONING' }), 24 * 3600_000))).toBe(CLONE_POLL_THINNED_MS) // still watching
    expect(clonePollMs(cloneView(null, 0))).toBe(CLONE_POLL_MS) // unknown → keep asking
  })

  it('★ ③ polling stops ONLY on a server verdict', () => {
    expect(clonePollMs(cloneView(cfg({ cloneStatus: 'CLONED_OK' }), 0))).toBeNull()
    expect(clonePollMs(cloneView(cfg({ cloneStatus: 'CLONE_FAILED' }), 0))).toBeNull()
  })

  it('★ ONLY CLONED_OK completes the repo step', () => {
    const states = ['NOT_CONFIGURED', 'CONFIGURED_NEVER_CLONED', 'CLONING', 'CLONE_FAILED'] as const
    for (const s of states) expect(isCloneDone(cloneView(cfg({ cloneStatus: s }), 0))).toBe(false)
    expect(isCloneDone(cloneView(cfg({ cloneStatus: 'CLONED_OK' }), 0))).toBe(true)
  })
})
