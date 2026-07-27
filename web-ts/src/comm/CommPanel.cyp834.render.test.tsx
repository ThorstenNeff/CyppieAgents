// @vitest-environment jsdom
// CYP-834 — the terminal protocol-skew banner + composer lock. `skew` is a SECOND terminal-alert, DISTINCT from
// `revoked`: its own dedicated role=alert node (comm-status-skew class + own text) and its own composer lock. A
// transient state shows neither. Mutation-proven.
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

describe('CYP-834 — CommPanel terminal protocol-skew banner + composer lock', () => {
  it('★ connection="skew" → dedicated role=alert/assertive node, comm-status-skew class, own text', () => {
    // MUT: route skew through the transient polite region (drop the skew alert sibling) → role/aria/class red.
    const { getByTestId } = render(<CommPanel {...base} connection="skew" />)
    const node = getByTestId('comm-status')
    expect(node.getAttribute('role')).toBe('alert')
    expect(node.getAttribute('aria-live')).toBe('assertive')
    expect(node.className).toContain('comm-status-skew')
    expect(node.textContent).toBe('App veraltet — bitte aktualisieren')
  })

  it('★ skew is DISTINCT from revoked (different text + class — never conflated)', () => {
    const skew = render(<CommPanel {...base} connection="skew" />).getByTestId('comm-status')
    const skewText = skew.textContent
    const skewClass = skew.className
    cleanup()
    const revoked = render(<CommPanel {...base} connection="revoked" />).getByTestId('comm-status')
    expect(skewText).not.toBe(revoked.textContent)
    expect(skewClass).not.toBe(revoked.className)
    expect(revoked.className).toContain('comm-status-revoked')
  })

  it('★ skew LOCKS the composer (terminal — a stopped connection cannot send), distinct from the revoked lock', () => {
    // MUT: don't lock on skew → the composer input renders; reds.
    const { queryByTestId } = render(<CommPanel {...base} connection="skew" />)
    expect(queryByTestId('comm-skew-lock')).not.toBeNull()
    expect(queryByTestId('comm-revoked-lock')).toBeNull()
    expect(queryByTestId('composer-input')).toBeNull()
  })

  it('★ a transient state (offline) shows NEITHER the skew alert NOR the skew lock (only terminal skew escalates)', () => {
    const { getByTestId, queryByTestId } = render(<CommPanel {...base} connection="offline" />)
    expect(getByTestId('comm-status').getAttribute('role')).toBe('status') // polite, not alert
    expect(getByTestId('comm-status').className).not.toContain('comm-status-skew')
    expect(queryByTestId('comm-skew-lock')).toBeNull()
  })
})
