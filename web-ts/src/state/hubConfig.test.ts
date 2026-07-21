// CYP-800 (N1.1) — teeth for the consumer layer over the endpoint registry. ★ = the fail-closed / single-source
// boundary: the registry returns null on an unknown hub (data), and a config that REQUIRES the active hub's bases
// turns that into a loud UnknownHubError (consumer-fail-closed) — never a silent global default. And the derived
// apiBase/wsBase must equal the registry's entry, so they can never drift from the single source.
import { describe, it, expect } from 'vitest'
import { hubConfigFrom, singleHubConfig } from './hubConfig'
import { hubRegistryOf, UnknownHubError } from '../net/hubRegistry'

describe('CYP-800 — hubConfig derives from the registry (single source)', () => {
  it('singleHubConfig derives apiBase/wsBase from the one registered hub', () => {
    const cfg = singleHubConfig({ endpoint: { apiBase: 'http://a', wsBase: 'ws://a' }, token: 't', operator: true })
    expect(cfg.apiBase).toBe('http://a')
    expect(cfg.wsBase).toBe('ws://a')
    expect(cfg.token).toBe('t')
    expect(cfg.operator).toBe(true)
  })

  it('★ apiBase/wsBase are a VIEW of the registry — they equal registry.endpointFor(hubId), never an independent value', () => {
    // RED if the fields ever diverge from the registry entry (a parallel global creeping back). The registry is the
    // single source; the convenience fields must be exactly its projection for the active hub.
    const cfg = singleHubConfig({ hubId: 'h9', endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: '', operator: false })
    const fromRegistry = cfg.registry.endpointFor(cfg.hubId)
    expect(fromRegistry).not.toBeNull()
    expect(cfg.apiBase).toBe(fromRegistry?.apiBase)
    expect(cfg.wsBase).toBe(fromRegistry?.wsBase)
  })

  it('★ hubConfigFrom throws UnknownHubError when the active hub is not in the registry (consumer fail-closed)', () => {
    // RED if the consumer ever coalesces a registry miss to a default endpoint instead of throwing — that silent
    // default is the cross-hub confusion N1 forbids, one layer up from endpointFor's null.
    const registry = hubRegistryOf({ a: { apiBase: 'http://a', wsBase: 'ws://a' } })
    expect(() => hubConfigFrom(registry, 'missing', { token: '', operator: false })).toThrow(UnknownHubError)
  })

  it('★ an EMPTY registry cannot yield a config — it throws, never a fabricated endpoint', () => {
    expect(() => hubConfigFrom(hubRegistryOf({}), 'local', { token: '', operator: false })).toThrow(UnknownHubError)
  })
})
