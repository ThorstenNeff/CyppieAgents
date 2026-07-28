import { describe, it, expect, beforeEach } from 'vitest'
import { useHubListStore } from './hubListStore'
import { emptyHubList } from './hubList'
import type { HubDescriptor } from '../types/generated/contract'

const hub = (hubId: string): HubDescriptor => ({
  hubId,
  name: hubId.toUpperCase(),
  online: true,
  defaultPort: 8443,
  lastSeen: 0,
  dhPubKey: `pk-${hubId}`,
})

describe('CYP-848 (Multi-Hub M1) — useHubListStore feeds the loaded list into state', () => {
  beforeEach(() => useHubListStore.setState({ ...emptyHubList }))

  it('starts unknown (loaded=false, no hubs)', () => {
    const s = useHubListStore.getState()
    expect(s.hubs).toEqual([])
    expect(s.loaded).toBe(false)
  })

  it('★ setHubList feeds the stubbed descriptor array into state (loaded, N hubs in order)', () => {
    useHubListStore.getState().setHubList([hub('local'), hub('alpha')])
    const s = useHubListStore.getState()
    expect(s.loaded).toBe(true)
    expect(s.hubs.map((h) => h.hubId)).toEqual(['local', 'alpha'])
  })

  it('★ a later setHubList REPLACES the list (not appends) — the store mirrors the latest control-plane result', () => {
    useHubListStore.getState().setHubList([hub('local'), hub('alpha')])
    useHubListStore.getState().setHubList([hub('beta')])
    expect(useHubListStore.getState().hubs.map((h) => h.hubId)).toEqual(['beta'])
  })

  it('★ CYP-852: failHubListLoad flips to the error outcome (loadError=true, not loaded) — distinct from empty', () => {
    useHubListStore.getState().failHubListLoad()
    const s = useHubListStore.getState()
    expect(s.loadError).toBe(true)
    expect(s.loaded).toBe(false)
    expect(s.hubs).toEqual([])
  })

  it('★ CYP-852: a successful setHubList after a failure CLEARS the error (recovery via retry)', () => {
    useHubListStore.getState().failHubListLoad()
    useHubListStore.getState().setHubList([hub('local')])
    const s = useHubListStore.getState()
    expect(s.loadError).toBe(false)
    expect(s.loaded).toBe(true)
    expect(s.hubs.map((h) => h.hubId)).toEqual(['local'])
  })
})
