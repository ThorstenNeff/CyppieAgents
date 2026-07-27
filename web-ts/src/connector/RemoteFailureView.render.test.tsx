// @vitest-environment jsdom
// CYP-826 (CYP-807-A2) — RemoteFailureView render teeth. The Zone-2 failure region renders the ISSUER arm at
// failed(issuer-not-trusted) (the parked CYP-805 leaf, finally user-visible), and NOTHING for transient/reconnectable
// states (the flag-2 point: reconnectable ≠ region) or additive-but-leaf-less causes. Mutation-proven.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { RemoteFailureView } from './RemoteFailureView'
import type { RemoteConnState } from '../state/remoteConnState'

afterEach(cleanup)
const ISSUER = 'remote.connect.error.issuerNotTrusted'

describe('CYP-826 A2 — RemoteFailureView: terminal-negative failure region, one arm, reconnectable≠region', () => {
  it('★ failed(issuer-not-trusted) → the IssuerNotTrusted arm renders (parked CYP-805 leaf, now user-visible)', () => {
    // MUT: return null for the issuer cause → this reds. This arm is what closes wire-complete≠user-visible.
    const { queryByTestId } = render(<RemoteFailureView state={{ phase: 'failed', cause: 'issuer-not-trusted' }} />)
    const arm = queryByTestId(ISSUER)
    expect(arm).not.toBeNull()
    expect(arm?.getAttribute('role')).toBe('alert') // terminal + assertive (leaf contract)
  })

  it('★ reconnecting → NO failure arm (reconnectable is transient/polite, NEVER the region) — the flag-2 point', () => {
    // MUT: route 'reconnecting' into the region → an arm would show; this reds. Reconnectable must not read as terminal.
    const { container, queryByTestId } = render(<RemoteFailureView state={{ phase: 'reconnecting' }} />)
    expect(queryByTestId(ISSUER)).toBeNull()
    expect(container.firstChild).toBeNull()
  })

  it('★ failed(non-issuer cause) → NO issuer arm (per-cause dispatch, additive leaf-less arms) — cause-distinct', () => {
    // MUT: render the issuer block for ANY failed cause (collapse the arm-switch) → this reds. Proves per-cause dispatch,
    // not a single generic failure render.
    for (const cause of ['security-tier', 'connect-refused', 'handshake-fail', 'remote-not-configured'] as const) {
      const { queryByTestId } = render(<RemoteFailureView state={{ phase: 'failed', cause }} />)
      expect(queryByTestId(ISSUER)).toBeNull()
      cleanup()
    }
  })

  it('terminal lost → no leaf yet (additive arm), renders nothing (distinct phase from reconnecting)', () => {
    const { container } = render(<RemoteFailureView state={{ phase: 'lost' }} />)
    expect(container.firstChild).toBeNull()
  })

  it('transient/non-failure states render nothing (idle/dialing/handshake/trust-check/connected)', () => {
    for (const phase of ['idle', 'dialing', 'handshake', 'trust-check', 'connected'] as const) {
      const { container } = render(<RemoteFailureView state={{ phase } as RemoteConnState} />)
      expect(container.firstChild).toBeNull()
      cleanup()
    }
  })
})
