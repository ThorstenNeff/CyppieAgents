// CYP-735 §3.2 — teeth for the guided first-run model (UIUX2 screen-spec 951823ab §6).
import { describe, it, expect } from 'vitest'
import { firstRunGateMode, firstRunProgress, firstIncompleteStep, setupErrorCueVisible, type FirstRunInputs } from './firstRunModel'
import type { SetupStatus } from '../workspace/setupStatus'

const inputs = (over: Partial<FirstRunInputs> = {}): FirstRunInputs => ({
  setup: { kind: 'configured' } as SetupStatus,
  apiKeySet: true,
  repoCloned: true,
  ...over,
})

describe('CYP-735 §3.2 — "done" means server-validated, never form-submitted', () => {
  it('both core steps genuinely done → the gate disappears (non-vacuous control)', () => {
    expect(firstRunGateMode(inputs())).toBe('transparent')
    expect(firstRunProgress(inputs())).toMatchObject({ apiKey: 'done', repo: 'done' })
  })

  it('★ ⑥.1 an UNRESOLVED config is `loading` — never a step, never "unconfigured"', () => {
    // Showing setup steps because we could not read the config would tell a configured operator to set up.
    expect(firstRunGateMode(inputs({ setup: { kind: 'unknown' } }))).toBe('loading')
    expect(firstRunGateMode(inputs({ setup: { kind: 'error' } }))).toBe('loading')
  })

  it('★ ⑥.2 repo "saved" is NOT "done" — an accepted URL is not a clonable repo', () => {
    // configured:true means the URL was accepted. It can still be a typo, a private repo without credentials, or
    // a dead host. Promoting that to "done" is the false-configured lie.
    const saved = inputs({ setup: { kind: 'configured' }, repoCloned: null })
    expect(firstRunProgress(saved).repo).toBe('saved')
    expect(firstRunGateMode(saved)).toBe('active') // and the gate must NOT go transparent on it
  })

  it('★ ⑥.2 without the clone seam the repo can never reach done — "cannot check" is not "it works"', () => {
    for (const cloned of [null, false]) {
      expect(firstRunProgress(inputs({ repoCloned: cloned })).repo).not.toBe('done')
      expect(firstRunGateMode(inputs({ repoCloned: cloned }))).toBe('active')
    }
  })

  it('★ ⑥.3 the API key is done only on the SERVER-held flag, not on an unresolved one', () => {
    expect(firstRunProgress(inputs({ apiKeySet: null })).apiKey).toBe('open')
    expect(firstRunProgress(inputs({ apiKeySet: false })).apiKey).toBe('open')
    expect(firstRunGateMode(inputs({ apiKeySet: null }))).toBe('active')
  })

  it('★ ⑥.5 team never gates completion — nobody is trapped for not having configured agents', () => {
    // team stays 'open' even when everything else is done, and the gate still goes transparent.
    const done = inputs()
    expect(firstRunProgress(done).team).toBe('open')
    expect(firstRunGateMode(done)).toBe('transparent')
    expect(firstIncompleteStep(firstRunProgress(done))).toBeNull() // team is never the resume target
  })

  it('resume lands on the first incomplete step, not always on step 1', () => {
    expect(firstIncompleteStep(firstRunProgress(inputs({ apiKeySet: null })))).toBe('apikey')
    expect(firstIncompleteStep(firstRunProgress(inputs({ repoCloned: null })))).toBe('repo')
  })

  it('★ an unresolved config never yields a "saved" repo either — nothing is inferred from silence', () => {
    expect(firstRunProgress(inputs({ setup: { kind: 'error' }, repoCloned: null })).repo).toBe('open')
    expect(firstRunProgress(inputs({ setup: { kind: 'unknown' }, repoCloned: null })).repo).toBe('open')
  })
})

describe('CYP-758 — setupErrorCueVisible: error overrides skip (the degraded-workspace error cue)', () => {
  const st = (kind: SetupStatus['kind']): SetupStatus => ({ kind }) as SetupStatus

  it('★ error ∧ skipped → visible: a config-load error must NOT be swallowed by the skip flag', () => {
    // The whole defect: skip hid the gate that carried the error. Mutation "skip hides error" (return error && !skip,
    // or drop the skip term to `false`) flips this to false → RED.
    expect(setupErrorCueVisible(st('error'), true)).toBe(true)
  })

  it('error ∧ NOT skipped → NOT this cue: the gate carries the error while the user is still in first-run', () => {
    expect(setupErrorCueVisible(st('error'), false)).toBe(false)
  })

  it('★ unconfigured ∧ skipped → NOT this cue: the setup PROMPT stays skip-suppressible (distinct signal)', () => {
    // The control that stops the fix from over-firing: skipping setup on an unconfigured hub must not raise an
    // ERROR cue — that is the UnconfiguredBanner's job, a different fact.
    expect(setupErrorCueVisible(st('unconfigured'), true)).toBe(false)
  })

  it('unknown / configured (either skip state) → never this cue', () => {
    for (const skipped of [true, false]) {
      expect(setupErrorCueVisible(st('unknown'), skipped)).toBe(false)
      expect(setupErrorCueVisible(st('configured'), skipped)).toBe(false)
    }
  })
})
