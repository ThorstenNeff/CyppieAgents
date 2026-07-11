// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel, Message1 } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [
  { id: 'po-frontend', name: 'PO ↔ Frontend', kind: 'DIRECT', members: ['po', 'frontend'] },
  { id: 'po-backend', name: 'PO ↔ Backend', kind: 'DIRECT', members: ['po', 'backend'] },
]

const base: CommPanelProps = {
  channels,
  selectedChannelId: 'po-frontend',
  onSelectChannel: () => {},
  messages: [],
  senderRole: () => null,
  connection: 'live',
  canWrite: true,
  sendError: null,
  onSend: () => {},
  historySize: () => 20,
}

describe('CommPanel (CYP-407 W9 part 2)', () => {
  it('renders exactly the server-provided channels; the selected one is aria-current', () => {
    const { getByTestId, queryByTestId } = render(<CommPanel {...base} />)
    expect(getByTestId('comm.channel.po-frontend').getAttribute('aria-current')).toBe('true')
    expect(getByTestId('comm.channel.po-backend').getAttribute('aria-current')).toBeNull()
    expect(queryByTestId('comm.channel.secret')).toBeNull() // no client-side channel invention
  })

  it('selecting a channel calls back', () => {
    const onSel = vi.fn()
    const { getByTestId } = render(<CommPanel {...base} onSelectChannel={onSel} />)
    fireEvent.click(getByTestId('comm.channel.po-backend'))
    expect(onSel).toHaveBeenCalledWith('po-backend')
  })

  it('shows the empty state, then messages with their sender', () => {
    const empty = render(<CommPanel {...base} />)
    expect(empty.queryByTestId('comm-empty')).not.toBeNull()
    cleanup()
    const msgs: Message1[] = [{ id: 'm1', channelId: 'po-frontend', from: 'frontend', body: 'hi there', ts: 0 }]
    const withMsg = render(<CommPanel {...base} messages={msgs} />)
    expect(withMsg.queryByTestId('comm-empty')).toBeNull()
    expect(withMsg.getByTestId('comm.message.m1').textContent).toContain('hi there')
  })

  it('disclosure: readonly hides the composer; denied/failed show the distinct error + keep the composer', () => {
    const ro = render(<CommPanel {...base} canWrite={false} />)
    expect(ro.queryByTestId('comm-readonly-hint')).not.toBeNull()
    expect(ro.queryByTestId('composer-input')).toBeNull()
    cleanup()
    const denied = render(<CommPanel {...base} sendError="comm_send_denied" />)
    expect(denied.queryByTestId('comm-send-denied')).not.toBeNull()
    expect(denied.queryByTestId('comm-send-failed')).toBeNull()
    expect(denied.queryByTestId('composer-input')).not.toBeNull()
    cleanup()
    const failed = render(<CommPanel {...base} sendError="comm_send_failed" />)
    expect(failed.queryByTestId('comm-send-failed')).not.toBeNull()
    expect(failed.queryByTestId('comm-send-denied')).toBeNull()
  })

  it('the connection banner distinguishes revoked from offline by TEXT, not just colour', () => {
    const revoked = render(<CommPanel {...base} connection="revoked" />)
    const revokedStatus = revoked.getByTestId('comm-status')
    expect(revokedStatus.className).toContain('comm-status-revoked')
    const revokedText = revokedStatus.textContent
    cleanup()
    const offlineText = render(<CommPanel {...base} connection="offline" />).getByTestId('comm-status').textContent
    // A terminal revoke must not read as a reconnectable blip: the copy itself carries the difference (the CSS
    // class/colour is structural and never fails). Mutating CONNECTION_TEXT.revoked to the offline text reds this.
    expect(revokedText).toBe('Zugriff entzogen')
    expect(revokedText).not.toBe(offlineText)
  })

  it('a revoked connection LOCKS the composer, even with canWrite true (CYP-437 #4)', () => {
    const live = render(<CommPanel {...base} />)
    expect(live.queryByTestId('composer-input')).not.toBeNull() // baseline: composer present when live
    cleanup()
    // canWrite stays true → proves the revoke overrides disclosure, not just a canWrite=false path
    const revoked = render(<CommPanel {...base} connection="revoked" canWrite={true} />)
    expect(revoked.queryByTestId('comm-revoked-lock')).not.toBeNull()
    expect(revoked.queryByTestId('composer-input')).toBeNull()
  })
})
