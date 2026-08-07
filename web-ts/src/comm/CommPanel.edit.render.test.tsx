// @vitest-environment jsdom
// CYP-906 (Edit E2/E3) — the "(bearbeitet)" marker (echo-only) + the operator-only edit affordance in the CommPanel
// timeline. Editing is NON-OPTIMISTIC: submitting calls onEditMessage (→ PUT) but does NOT locally mutate the message —
// the body changes only when the server echo folds through applyMessage (proven in editedMessage.test.ts).
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, cleanup, fireEvent, within } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel, DeliveredMessage } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [{ id: 'c', name: 'C', kind: 'DIRECT', members: ['operator', 'backend'] }]
const dm = (id: string, body: string, from = 'operator', edited?: number): DeliveredMessage =>
  ({ message: { id, channelId: 'c', from, body, ts: 0 }, ...(edited !== undefined ? { editedAt: edited } : {}) }) as DeliveredMessage
const base: CommPanelProps = {
  channels, selectedChannelId: 'c', onSelectChannel: () => {}, messages: [], senderRole: () => null,
  connection: 'live', canWrite: true, sendError: null, onSend: () => {}, historySize: () => 20,
}

describe('CYP-906 E2 — "(bearbeitet)" marker (echo-only)', () => {
  it('★ marker renders from server editedAt; ABSENT when the message was never edited', () => {
    // MUT: render the marker unconditionally → the un-edited message gets one → reds. MUT: drop it → the edited one loses it → reds.
    const { getByTestId, queryByTestId } = render(<CommPanel {...base} messages={[dm('a', 'x'), dm('b', 'y', 'operator', 123)]} />)
    expect(queryByTestId('comm.message.a.edited')).toBeNull()
    expect(getByTestId('comm.message.b.edited').textContent).toContain('bearbeitet')
  })
})

describe('CYP-906 E3 — operator-only edit affordance', () => {
  it('★ no edit trigger when canEdit is absent', () => {
    const { queryByTestId } = render(<CommPanel {...base} messages={[dm('a', 'x')]} />)
    expect(queryByTestId('comm.message.a.edit')).toBeNull()
  })

  it('★ no trigger for a non-editable sender (canEdit → false)', () => {
    // MUT: render the trigger regardless of canEdit → this reds.
    const { queryByTestId } = render(<CommPanel {...base} messages={[dm('a', 'x', 'backend')]} canEdit={() => false} onEditMessage={() => {}} />)
    expect(queryByTestId('comm.message.a.edit')).toBeNull()
  })

  it('★ trigger → edit mode → submit calls onEditMessage, and the timeline body is NOT locally mutated (non-optimistic)', () => {
    const onEditMessage = vi.fn()
    const { getByTestId, queryByTestId } = render(
      <CommPanel {...base} messages={[dm('a', 'hello')]} canEdit={(from) => from === 'operator'} onEditMessage={onEditMessage} />,
    )
    fireEvent.click(getByTestId('comm.message.a.edit')) // enter edit mode
    expect(getByTestId('comm.message.a.edit.cancel')).toBeTruthy()
    const editInput = within(getByTestId('comm.message.a')).getByTestId('composer-input') as HTMLInputElement
    fireEvent.change(editInput, { target: { value: 'edited body' } })
    fireEvent.keyDown(editInput, { key: 'Enter' })
    expect(onEditMessage).toHaveBeenCalledWith('a', 'edited body')
    // ★ non-optimistic: the rendered message body is UNCHANGED (still 'hello') — no local mutation on submit.
    expect(within(getByTestId('comm.message.a')).getByText('hello')).toBeTruthy()
    expect(queryByTestId('comm.message.a.edit.cancel')).toBeNull() // edit mode exited
  })
})
