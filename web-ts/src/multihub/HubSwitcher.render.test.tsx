// @vitest-environment jsdom
// CYP-852 (Multi-Hub M2) — render teeth for the hub switcher. Drives the M1 useHubListStore directly (setState) and
// the switch behavior via an injected onSwitch/onRetry. Binds Tester2's MC-1 (no cross-hub axis leak) / MC-3 (N in
// list) / MC-5 (render-honesty UNKNOWN pre-arming) plus the spec teeth: axis-c-absent, empty≠load-error, non-optimistic.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { HubSwitcher } from './HubSwitcher'
import { useHubListStore } from '../state/hubListStore'
import { emptyHubList } from '../state/hubList'
import type { HubDescriptor } from '../types/generated/contract'

const hub = (hubId: string, over: Partial<HubDescriptor> = {}): HubDescriptor => ({
  hubId,
  name: hubId.toUpperCase(),
  online: true,
  defaultPort: 8443,
  lastSeen: 1_700_000_000_000,
  dhPubKey: `pk-${hubId}`,
  ...over,
})

afterEach(() => {
  cleanup()
  useHubListStore.setState({ ...emptyHubList })
})

const noop = async () => {}
const renderSwitcher = (props: Partial<Parameters<typeof HubSwitcher>[0]> = {}) =>
  render(<HubSwitcher activeHubId="local" onSwitch={props.onSwitch ?? noop} onRetry={props.onRetry ?? (() => {})} />)

describe('CYP-852 — HubSwitcher (list render, 4 axes never conflated)', () => {
  it('★ MC-3: N hubs → N list entries, in the control-plane order', () => {
    useHubListStore.getState().setHubList([hub('local'), hub('alpha'), hub('beta')])
    const { getByTestId, container } = renderSwitcher()
    for (const id of ['local', 'alpha', 'beta']) expect(getByTestId(`hub-switcher.entry.${id}`)).toBeTruthy()
    const names = [...container.querySelectorAll('[data-testid^="hub-switcher.name."]')].map((n) => n.textContent)
    expect(names).toEqual(['LOCAL', 'ALPHA', 'BETA'])
  })

  it('★ MC-1: reachability is its OWN neutral marker — online:false renders an Offline marker, NOT a trust state', () => {
    // The exact conflation the honesty core forbids: an offline hub must not read as "untrusted". MUT: render online
    // via the trust badge (drop the separate reach marker / feed online into trust) → the offline hub loses its
    // Offline marker or gains a non-unknown trust → reds.
    useHubListStore.getState().setHubList([hub('local', { online: true }), hub('beta', { online: false })])
    const { getByTestId } = renderSwitcher()
    expect(getByTestId('hub-switcher.reach.local').getAttribute('data-online')).toBe('true')
    expect(getByTestId('hub-switcher.reach.beta').getAttribute('data-online')).toBe('false')
    // …and the offline hub's TRUST is still an independent UNKNOWN badge, never conflated with reachability:
    expect(getByTestId('hub.trust.beta.unknown')).toBeTruthy()
  })

  it('★ MC-1 (no cross-hub leak): each hub carries its OWN four axes — hub A’s online does not leak to hub B', () => {
    useHubListStore.getState().setHubList([hub('local', { online: true, name: 'Local' }), hub('beta', { online: false, name: 'Beta' })])
    const { getByTestId } = renderSwitcher()
    expect(getByTestId('hub-switcher.name.local').textContent).toBe('Local')
    expect(getByTestId('hub-switcher.name.beta').textContent).toBe('Beta')
    expect(getByTestId('hub-switcher.reach.local').getAttribute('data-online')).toBe('true')
    expect(getByTestId('hub-switcher.reach.beta').getAttribute('data-online')).toBe('false')
  })

  it('★ MC-5 (render-honesty): every entry’s axis-a trust badge is UNKNOWN pre-arming — NEVER trusted', () => {
    // §2b: pre-arming the switcher feeds trust=null → UNKNOWN, never a live "trusted" decision. Even a descriptor that
    // claims issuerTrust=TRUSTED must not make the axis-a badge trusted (different axis). MUT: feed a non-null trust /
    // derive trust from the descriptor → a .trusted pill appears → reds.
    useHubListStore.getState().setHubList([hub('local', { issuerTrust: 'TRUSTED' }), hub('beta')])
    const { getByTestId, queryByTestId } = renderSwitcher()
    expect(getByTestId('hub.trust.local.unknown')).toBeTruthy()
    expect(getByTestId('hub.trust.beta.unknown')).toBeTruthy()
    expect(queryByTestId('hub.trust.local.trusted')).toBeNull()
  })

  it('★ axis-c ABSENT: issuerTrust never appears in the switcher (Zone-clean negative assertion, ruling 7dda489f)', () => {
    // NOT_TRUSTED / REMOTE_NOT_CONFIGURED are Zone-2 connect verdicts (M4), never a switcher badge. MUT: render an
    // issuer marker in the entry → its text/testid appears here → reds.
    useHubListStore.getState().setHubList([hub('local', { issuerTrust: 'NOT_TRUSTED' }), hub('beta', { issuerTrust: 'REMOTE_NOT_CONFIGURED' })])
    const { container } = renderSwitcher()
    expect(container.textContent).not.toMatch(/NOT_TRUSTED|REMOTE_NOT_CONFIGURED|issuer/i)
    expect(container.querySelector('[data-testid*="issuer"]')).toBeNull()
  })

  it('★ empty ≠ load-error ≠ unknown: a failed load → Error+Retry, never the "no hubs" empty', () => {
    useHubListStore.getState().failHubListLoad()
    const { getByTestId, queryByTestId } = renderSwitcher()
    expect(getByTestId('hub-switcher.error')).toBeTruthy()
    expect(queryByTestId('hub-switcher.empty')).toBeNull()
    expect(queryByTestId('hub-switcher')).toBeNull() // no list on an error
  })

  it('★ a successful zero-hub load → the HONEST empty state (not an error, not nothing)', () => {
    useHubListStore.getState().setHubList([])
    const { getByTestId, queryByTestId } = renderSwitcher()
    expect(getByTestId('hub-switcher.empty')).toBeTruthy()
    expect(queryByTestId('hub-switcher.error')).toBeNull()
  })

  it('★ not-loaded-yet (unknown) → renders NOTHING (never a confident empty or a phantom list)', () => {
    // store stays at emptyHubList (loaded=false, loadError=false)
    const { queryByTestId } = renderSwitcher()
    expect(queryByTestId('hub-switcher')).toBeNull()
    expect(queryByTestId('hub-switcher.empty')).toBeNull()
    expect(queryByTestId('hub-switcher.error')).toBeNull()
  })

  it('★ non-optimistic switch: the active marker follows activeHubId, and a click does NOT move it optimistically', () => {
    // MUT: mark active from an in-flight click / setState optimistic → aria-current jumps before server-confirm → reds.
    const onSwitch = vi.fn().mockResolvedValue(undefined)
    useHubListStore.getState().setHubList([hub('local'), hub('beta')])
    const { getByTestId } = renderSwitcher({ onSwitch })
    expect(getByTestId('hub-switcher.entry.local').getAttribute('aria-current')).toBe('true')
    expect(getByTestId('hub-switcher.entry.beta').getAttribute('aria-current')).toBeNull()
    fireEvent.click(getByTestId('hub-switcher.entry.beta'))
    expect(onSwitch).toHaveBeenCalledWith('beta')
    // active marker has NOT moved (activeHubId prop is the source of truth, not the click):
    expect(getByTestId('hub-switcher.entry.local').getAttribute('aria-current')).toBe('true')
    expect(getByTestId('hub-switcher.entry.beta').getAttribute('aria-current')).toBeNull()
  })

  it('★ switch-to-active is a no-op (clicking the current hub does not fire onSwitch)', () => {
    const onSwitch = vi.fn().mockResolvedValue(undefined)
    useHubListStore.getState().setHubList([hub('local'), hub('beta')])
    const { getByTestId } = renderSwitcher({ onSwitch })
    fireEvent.click(getByTestId('hub-switcher.entry.local')) // local IS active
    expect(onSwitch).not.toHaveBeenCalled()
  })
})
