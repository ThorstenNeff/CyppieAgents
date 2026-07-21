// CYP-800 (N1.1) — teeth for the per-hubId endpoint registry. ★ = the honesty boundary: an unknown hub resolves to
// null (fail-closed), NEVER to some ambient/first-entry fallback — a wrong-hub endpoint is the cross-hub confusion
// N1 exists to prevent, and is strictly worse than resolving nothing.
import { describe, it, expect } from 'vitest'
import { hubRegistryOf } from './hubRegistry'

describe('CYP-800 — hub endpoint registry', () => {
  const reg = () =>
    hubRegistryOf({
      a: { apiBase: 'http://a', wsBase: 'ws://a' },
      b: { apiBase: 'http://b', wsBase: 'ws://b' },
    })

  it('endpointFor returns the registered hub’s endpoint', () => {
    expect(reg().endpointFor('a')).toEqual({ apiBase: 'http://a', wsBase: 'ws://a' })
    expect(reg().endpointFor('b')).toEqual({ apiBase: 'http://b', wsBase: 'ws://b' })
  })

  it('★ an UNKNOWN hub resolves to null — fail-closed, never a fallback to another hub', () => {
    // RED if endpointFor ever coalesces a miss to a default/first entry: that would hand hub C a DIFFERENT hub’s
    // endpoint (wrong credential target, wrong socket) — exactly the cross-hub leak N1 forbids.
    expect(reg().endpointFor('c')).toBeNull()
    expect(hubRegistryOf({}).endpointFor('a')).toBeNull() // empty registry resolves nothing, not a guess
  })

  it('hubIds lists exactly the registered hubs', () => {
    expect(reg().hubIds()).toEqual(['a', 'b'])
    expect(hubRegistryOf({}).hubIds()).toEqual([])
  })

  it('★ a later mutation of the source object cannot silently re-point a hub (defensive copy)', () => {
    // RED if the registry aliases the caller’s objects: a hub’s endpoint could then change under it after
    // construction, a stale-source drift the single-source guarantee must not allow.
    const src = { a: { apiBase: 'http://a', wsBase: 'ws://a' } }
    const registry = hubRegistryOf(src)
    src.a.apiBase = 'http://EVIL'
    expect(registry.endpointFor('a')?.apiBase).toBe('http://a')
    // and a mutation of the RETURNED endpoint must not corrupt the registry’s copy either. HubEndpoint is
    // `readonly` (tsc blocks this for typed callers), so cast to simulate an untyped JS consumer mutating it — the
    // runtime defensive copy must still hold.
    const got = registry.endpointFor('a')!
    ;(got as { apiBase: string }).apiBase = 'http://ALSO-EVIL'
    expect(registry.endpointFor('a')?.apiBase).toBe('http://a')
  })
})
