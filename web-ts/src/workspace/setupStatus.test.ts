// CYP-735 — teeth for the setup status (UIUX2 spec ad741f60 §3.1/§4). The whole point is that "we don't know"
// never collapses into "not configured": that collapse tells a correctly-configured operator to go configure.
import { describe, it, expect } from 'vitest'
import { setupStatusOf, mayPromptSetup, type SetupStatus } from './setupStatus'
import type { RepoConfigView } from '../types/generated/contract'

const cfg = (configured: boolean): RepoConfigView => ({ configured })

describe('CYP-735 — setup status is four distinct states, and only one may prompt', () => {
  it('a server-stated configured:false is the ONLY prompting state (non-vacuous control)', () => {
    const status = setupStatusOf(cfg(false), false)
    expect(status).toEqual({ kind: 'unconfigured' })
    expect(mayPromptSetup(status)).toBe(true)
  })

  it('a server-stated configured:true never prompts', () => {
    expect(mayPromptSetup(setupStatusOf(cfg(true), false))).toBe(false)
  })

  it('★ NOT-YET-LOADED is `unknown`, never `unconfigured` — absence of an answer is not an answer', () => {
    const status = setupStatusOf(null, false)
    expect(status).toEqual({ kind: 'unknown' })
    expect(mayPromptSetup(status)).toBe(false) // no "set up your hub" before the config has spoken
  })

  it('★ a LOAD ERROR is `error`, never `unconfigured` — the collapse that lies to a configured operator', () => {
    // This is the defect the union exists to prevent: a failed load leaves `config === null`, and reading that as
    // "not configured" tells someone whose hub works to go set it up. Confidently wrong beats quietly wrong here.
    const status = setupStatusOf(null, true)
    expect(status).toEqual({ kind: 'error' })
    expect(mayPromptSetup(status)).toBe(false)
  })

  it('★ a load error wins even when a stale config is still in hand — we no longer know it is current', () => {
    // Order matters: the error must not be masked by a previously-loaded value.
    expect(setupStatusOf(cfg(true), true)).toEqual({ kind: 'error' })
    expect(setupStatusOf(cfg(false), true)).toEqual({ kind: 'error' })
  })

  it('★ F1 — a 200 whose body lacks `configured` is UNKNOWN, never unconfigured (Tester2)', () => {
    // The REST client casts instead of validating, so a missing field arrives as `undefined` — falsy, and
    // therefore silently "not configured". That put a setup banner on a correctly-configured hub. An
    // uninterpretable body is exactly as unknown as no body at all.
    for (const body of [{}, { configured: undefined }, { configured: 'true' }, { configured: 1 }, { configured: null }]) {
      const status = setupStatusOf(body as never, false)
      expect(status).toEqual({ kind: 'unknown' })
      expect(mayPromptSetup(status)).toBe(false)
    }
  })

  it('★ exactly ONE of the four states prompts — scanned, not enumerated by hand', () => {
    const all: SetupStatus[] = [{ kind: 'unknown' }, { kind: 'error' }, { kind: 'unconfigured' }, { kind: 'configured' }]
    expect(all.filter(mayPromptSetup)).toEqual([{ kind: 'unconfigured' }])
  })
})
