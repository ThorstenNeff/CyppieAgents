// @vitest-environment jsdom
// CYP-855 (Multi-Hub M5) — render teeth for the connect-progression chrome (CYP-827). Unit slice (authoring, not
// mounting): the honesty-critical states + the RemoteConnState→ProgressionState mapping. Zone-separation (§5.5) is a
// mount concern (M4) — here we pin that the chrome is its OWN Zone-2 namespace (remote.progression.*), not Zone-1 badges.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent } from '@testing-library/react'
import { ConnectProgressionChrome, progressionStateFor, type ProgressionState } from './ConnectProgressionChrome'
import type { RemoteConnState } from '../state/remoteConnState'

afterEach(cleanup)

const ALL: ProgressionState[] = ['dialing', 'handshake', 'trust-check', 'reconnecting', 'connected']

describe('CYP-855 — ConnectProgressionChrome (Zone-2 in-flight, CYP-827)', () => {
  it('★ each state renders its verbatim copy + Compose-parity tag', () => {
    const expected: Record<ProgressionState, { text: string; tag: string }> = {
      dialing: { text: 'Relay wird gewählt …', tag: 'remote.connect.relayDialing' },
      handshake: { text: 'E2E-Handshake …', tag: 'remote.connect.e2eHandshake' },
      'trust-check': { text: 'Hub-Vertrauen wird geprüft …', tag: 'remote.connect.trustCheck' },
      reconnecting: { text: 'Verbindung unterbrochen — verbinde neu …', tag: 'remote.relayDrop' },
      connected: { text: 'Verbunden', tag: 'remote.connect.connected' },
    }
    for (const s of ALL) {
      const { getByTestId } = render(<ConnectProgressionChrome state={s} />)
      const node = getByTestId(`remote.progression.${s}`)
      expect(node.textContent).toContain(expected[s].text)
      expect(node.getAttribute('data-tag')).toBe(expected[s].tag)
      cleanup()
    }
  })

  it('★ the `●` LIVE marker appears ONLY at connected — never optimistically before it (§5.1)', () => {
    // MUT: render the live marker in a pre-connected state → optimistic anticipation → reds.
    for (const s of ['dialing', 'handshake', 'trust-check', 'reconnecting'] as ProgressionState[]) {
      const { queryByTestId } = render(<ConnectProgressionChrome state={s} />)
      expect(queryByTestId('remote.progression.live')).toBeNull()
      cleanup()
    }
    const { getByTestId } = render(<ConnectProgressionChrome state="connected" />)
    expect(getByTestId('remote.progression.live').textContent).toBe('●')
  })

  it('★ trust-check carries the PROVISIONAL disclosure — "vorläufig", NEVER "verifiziert/verified" (§5.2)', () => {
    // MUT: drop the disclosure OR make it say "verifiziert" → over-say → reds.
    const { getByTestId } = render(<ConnectProgressionChrome state="trust-check" />)
    const disc = getByTestId('remote.connect.trustProvisional')
    expect(disc.textContent).toContain('vorläufig')
    expect(disc.textContent).not.toMatch(/verifiziert|verified|vertraut/i)
    cleanup()
    // …and NO other state fabricates the disclosure:
    const { queryByTestId: q2 } = render(<ConnectProgressionChrome state="dialing" />)
    expect(q2('remote.connect.trustProvisional')).toBeNull()
  })

  it('★ reconnecting is neutral + in-flight, NOT a terminal/alarm arm (§5.3)', () => {
    // MUT: an alarm/error tone or a failure-region role → reds. reconnecting stays a polite in-flight step.
    const { getByTestId } = render(<ConnectProgressionChrome state="reconnecting" />)
    const node = getByTestId('remote.progression.reconnecting')
    expect(node.getAttribute('role')).toBe('status') // NOT role=alert (that is the terminal failure region)
    expect(node.className).not.toMatch(/alarm|error|danger|terminal/)
    expect(node.textContent).toContain('verbinde neu')
  })

  it('★ every progression state is a11y POLITE (role=status/aria-live=polite) — never assertive (§5.4)', () => {
    // MUT: any progression state assertive → announce-storm → reds.
    for (const s of ALL) {
      const { getByTestId } = render(<ConnectProgressionChrome state={s} />)
      const node = getByTestId(`remote.progression.${s}`)
      expect(node.getAttribute('role')).toBe('status')
      expect(node.getAttribute('aria-live')).toBe('polite')
      cleanup()
    }
  })

  it('★ liveness ≠ trust: the connected `●` is a liveness marker, NOT an axis-a hub-trust element (§5.6)', () => {
    const { getByTestId, container } = render(<ConnectProgressionChrome state="connected" />)
    expect(getByTestId('remote.progression.live').className).toContain('remote-progression-live')
    // the chrome carries NO Zone-1 hub-trust / tier badge (those coexist elsewhere, never inside the progression card):
    expect(container.querySelector('[class*="hub-trust"]')).toBeNull()
    expect(container.querySelector('[data-testid*="hub.trust"]')).toBeNull()
  })

  it('connected offers the forward action "Loslegen" (no dead-end) and fires it', () => {
    const onEnter = vi.fn()
    const { getByTestId } = render(<ConnectProgressionChrome state="connected" onEnterWorkspace={onEnter} />)
    fireEvent.click(getByTestId('remote.connect.toWorkspace'))
    expect(onEnter).toHaveBeenCalledTimes(1)
  })
})

describe('CYP-855 — progressionStateFor (RemoteConnState → in-flight progression)', () => {
  it('★ maps the five machine progression phases to their state', () => {
    const cases: [RemoteConnState, ProgressionState][] = [
      [{ phase: 'dialing' }, 'dialing'],
      [{ phase: 'handshake' }, 'handshake'],
      [{ phase: 'trust-check' }, 'trust-check'],
      [{ phase: 'reconnecting' }, 'reconnecting'],
      [{ phase: 'connected' }, 'connected'],
    ]
    for (const [conn, expected] of cases) expect(progressionStateFor(conn)).toBe(expected)
  })

  it('★ idle / failed / lost are NOT progression steps → null (failed/lost = Failure-Region CYP-823)', () => {
    // MUT: mapping failed/lost to a progression state → a terminal outcome would render as an in-flight step → reds.
    expect(progressionStateFor({ phase: 'idle' })).toBeNull()
    expect(progressionStateFor({ phase: 'failed', cause: 'connect-refused' })).toBeNull()
    expect(progressionStateFor({ phase: 'failed', cause: 'issuer-not-trusted' })).toBeNull()
    expect(progressionStateFor({ phase: 'lost' })).toBeNull()
  })
})
