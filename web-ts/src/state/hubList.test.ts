import { describe, it, expect } from 'vitest'
import { emptyHubList, loadHubList, failHubList } from './hubList'
import type { HubDescriptor } from '../types/generated/contract'

const hub = (hubId: string, over: Partial<HubDescriptor> = {}): HubDescriptor => ({
  hubId,
  name: hubId.toUpperCase(),
  online: true,
  defaultPort: 8443,
  lastSeen: 0,
  dhPubKey: `pk-${hubId}`,
  ...over,
})

describe('CYP-848 (Multi-Hub M1) — hub-list consume from the stubbed /api/cp/hubs descriptor array', () => {
  it('the initial state is UNKNOWN, not an empty result and not an error (loaded=false, loadError=false)', () => {
    // The switcher must not render "not loaded yet" as a confident "no hubs". MUT: emptyHubList.loaded=true → reds.
    expect(emptyHubList).toEqual({ hubs: [], loaded: false, loadError: false })
  })

  it('★ empty descriptor array → an HONEST empty (loaded=true, zero hubs, no error) — distinct from unknown AND error', () => {
    // A successful empty result IS "zero hubs", not "unknown". MUT: loadHubList returning loaded=false on [] → reds.
    const s = loadHubList([])
    expect(s.hubs).toEqual([])
    expect(s.loaded).toBe(true)
    expect(s.loadError).toBe(false)
  })

  it('★ CYP-852: a FAILED load → loadError=true, NOT loaded, no hubs — the switcher shows Error+Retry, not "no hubs"', () => {
    // empty ≠ load-error: a failure must be distinct from an honest empty. MUT: failHubList returning loaded=true (an
    // empty "loaded" result) → the switcher would render "no hubs" on a failure → reds.
    const s = failHubList()
    expect(s).toEqual({ hubs: [], loaded: false, loadError: true })
  })

  it('★ CYP-852: a successful load CLEARS a prior load-error (fresh result supersedes the failure)', () => {
    // MUT: loadHubList leaving loadError as-is → a recovered load would still render the error → reds.
    const s = loadHubList([hub('local')])
    expect(s.loadError).toBe(false)
    expect(s.loaded).toBe(true)
  })

  it('★ a single hub → a one-entry list', () => {
    const s = loadHubList([hub('local')])
    expect(s.loaded).toBe(true)
    expect(s.hubs.map((h) => h.hubId)).toEqual(['local'])
  })

  it('★ N hubs → all of them, in the control-plane order (never dropped or reordered)', () => {
    // MUT: any slice/dedup/sort in loadHubList → the order or count changes → reds.
    const s = loadHubList([hub('local'), hub('alpha'), hub('beta')])
    expect(s.hubs.map((h) => h.hubId)).toEqual(['local', 'alpha', 'beta'])
    expect(s.hubs).toHaveLength(3)
  })

  it('★ preserves each descriptor verbatim (name/online/issuerTrust survive — the switcher fields)', () => {
    const d = hub('remote', { name: 'Remote One', online: false, issuerTrust: 'NOT_TRUSTED' })
    const s = loadHubList([d])
    expect(s.hubs[0]).toEqual(d)
  })

  it('★ defensive copy — mutating the caller’s array after load cannot re-write the stored list', () => {
    // MUT: store the array by reference (drop the [...descriptors] copy) → the push leaks in → reds.
    const src = [hub('local')]
    const s = loadHubList(src)
    src.push(hub('injected'))
    expect(s.hubs.map((h) => h.hubId)).toEqual(['local'])
  })
})
