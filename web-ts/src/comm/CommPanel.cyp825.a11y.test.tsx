// @vitest-environment jsdom
// CYP-825 (AT-12) — the TERMINAL comm-revoke escalates to a DEDICATED role=alert node (implicit assertive+atomic), while
// the 3 TRANSIENT states (live/connecting/offline) stay a polite role=status region. The fix is two separate SIBLING
// nodes (so the alert mounts fresh on →revoked and announces at once), NOT flipping aria-live on one shared node (SRs
// cache the initial polite value). These teeth pin: revoked=alert/assertive, transients=status/polite, mutual
// exclusivity, and — the load-bearing non-vacuity half — that revoked is NEVER announced politely (the exact defect).
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel } from '../types/generated/contract'

afterEach(cleanup)
const channels: Channel[] = [{ id: 'c', name: 'C', kind: 'DIRECT', members: ['po', 'frontend'] }]
const base: CommPanelProps = {
  channels, selectedChannelId: 'c', onSelectChannel: () => {}, messages: [], senderRole: () => null,
  connection: 'live', canWrite: true, sendError: null, onSend: () => {}, historySize: () => 20,
}

describe('CYP-825 — terminal revoke escalates to a dedicated role=alert; transients stay polite', () => {
  it('★ connection="revoked" → the comm-status node is role=alert + aria-live=assertive', () => {
    // MUT: leave revoked on the shared role=status/aria-live=polite node → both attribute checks red. Proves escalation.
    const { getByTestId } = render(<CommPanel {...base} connection="revoked" />)
    const node = getByTestId('comm-status')
    expect(node.getAttribute('role')).toBe('alert')
    expect(node.getAttribute('aria-live')).toBe('assertive')
  })

  it('★ revoked is NEVER announced politely (the exact AT-12 defect) — aria-live must not be "polite"', () => {
    // MUT: if revoked shared the polite region → aria-live==='polite'; this reds. The non-vacuity load-bearing tooth.
    const { getByTestId } = render(<CommPanel {...base} connection="revoked" />)
    expect(getByTestId('comm-status').getAttribute('aria-live')).not.toBe('polite')
  })

  for (const state of ['live', 'connecting', 'offline'] as const) {
    it(`★ transient "${state}" stays a polite role=status region and is NOT escalated to alert (over-alarm is also dishonest)`, () => {
      // MUT: escalate a transient to role=alert → the role check + the queryByRole('alert') null-check red.
      const { getByTestId, queryByRole } = render(<CommPanel {...base} connection={state} />)
      const node = getByTestId('comm-status')
      expect(node.getAttribute('role')).toBe('status')
      expect(node.getAttribute('aria-live')).toBe('polite')
      expect(queryByRole('alert')).toBeNull()
    })
  }

  it('★ dedicated + mutually-exclusive: exactly one comm-status renders; for revoked it IS the alert node', () => {
    // MUT: render both siblings unconditionally (or keep one shared node) → the length/exclusivity assertions red.
    const revoked = render(<CommPanel {...base} connection="revoked" />)
    expect(revoked.queryAllByTestId('comm-status')).toHaveLength(1)
    expect(revoked.getByRole('alert').getAttribute('data-testid')).toBe('comm-status')
    cleanup()
    const offline = render(<CommPanel {...base} connection="offline" />)
    expect(offline.queryAllByTestId('comm-status')).toHaveLength(1)
    expect(offline.queryByRole('alert')).toBeNull()
  })

  it('★ →revoked MOUNTS A FRESH node, not the reused polite node (pins the sibling-split, not a same-position ternary)', () => {
    // MUT: refactor the two sibling {cond && <div>} into a same-position ternary → React reuses the one DOM node and
    // flips aria-live (the SR-unreliable trap) → after===before; this .not.toBe reds. Pins the fix's actual mechanism —
    // the other teeth only render fresh per value and would stay green under that refactor.
    const { getByTestId, rerender } = render(<CommPanel {...base} connection="offline" />)
    const before = getByTestId('comm-status')
    expect(before.getAttribute('aria-live')).toBe('polite')
    rerender(<CommPanel {...base} connection="revoked" />)
    const after = getByTestId('comm-status')
    expect(after.getAttribute('role')).toBe('alert')
    expect(after.getAttribute('aria-live')).toBe('assertive')
    expect(after).not.toBe(before) // fresh mount — a same-position ternary would REUSE (after===before) and fail here
  })

  it('revoked keeps the visible text + errorContainer class (only the a11y announcement escalates, visual unchanged)', () => {
    const { getByTestId } = render(<CommPanel {...base} connection="revoked" />)
    const node = getByTestId('comm-status')
    expect(node.textContent).toBe('Zugriff entzogen')
    expect(node.className).toContain('comm-status-revoked')
  })
})
