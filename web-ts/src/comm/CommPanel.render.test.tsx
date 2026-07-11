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

  it('the connection banner reflects the socket state', () => {
    const { getByTestId } = render(<CommPanel {...base} connection="revoked" />)
    expect(getByTestId('comm-status').className).toContain('comm-status-revoked')
  })
})
