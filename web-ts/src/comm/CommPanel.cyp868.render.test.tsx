// @vitest-environment jsdom
// CYP-868 (OS-A) — CommPanel renders orchestration TYPE (TASK/STATUS badge) + reply THREADING (inReplyTo tree) from
// server-stamped meta ONLY. Honesty (render ≠ authority): absent meta ⇒ plain NOTE (no badge), never a fabricated
// TASK/STATUS; a reply link renders only from server inReplyTo pointing at a PRESENT parent.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel, DeliveredMessage, MessageMeta } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [{ id: 'c', name: 'C', kind: 'DIRECT', members: ['po', 'frontend'] }]
const dm = (id: string, meta?: MessageMeta | null): DeliveredMessage => ({
  message: { id, channelId: 'c', from: 'po', body: `b-${id}`, ts: 0, ...(meta !== undefined ? { meta } : {}) },
})
const base: CommPanelProps = {
  channels, selectedChannelId: 'c', onSelectChannel: () => {}, messages: [], senderRole: () => null,
  connection: 'live', canWrite: true, sendError: null, onSend: () => {}, historySize: () => 20,
}

describe('CYP-868 — orchestration message-type badge (server-stamped only)', () => {
  it('★ a TASK message renders the TASK badge; a STATUS message the STATUS badge (server kind, verbatim word)', () => {
    // MUT: drop the kind-render → the badges vanish → reds.
    const { getByTestId } = render(<CommPanel {...base} messages={[dm('t', { kind: 'TASK' }), dm('s', { kind: 'STATUS' })]} />)
    expect(getByTestId('comm.message.t.kind').textContent).toBe('TASK')
    expect(getByTestId('comm.message.t.kind').getAttribute('data-kind')).toBe('TASK')
    expect(getByTestId('comm.message.s.kind').textContent).toBe('STATUS')
  })

  it('★ a NOTE message and an ABSENT-meta message render NO kind badge — never a fabricated TASK/STATUS (honesty)', () => {
    // MUT: default absent→TASK/STATUS, or badge NOTE as if significant → a fabricated badge appears → reds.
    const { queryByTestId } = render(<CommPanel {...base} messages={[dm('n', { kind: 'NOTE' }), dm('a')]} />)
    expect(queryByTestId('comm.message.n.kind')).toBeNull() // explicit NOTE = quiet default, no badge
    expect(queryByTestId('comm.message.a.kind')).toBeNull() // absent meta = plain NOTE-equivalent, no badge
  })
})

describe('CYP-868 — reply threading (server inReplyTo only)', () => {
  it('★ a reply to a PRESENT parent renders the reply reference + indent depth (from the server link)', () => {
    // MUT: drop the reply-tree render → the reference/indent vanish → reds.
    const { getByTestId } = render(<CommPanel {...base} messages={[dm('p'), dm('r', { inReplyTo: 'p' })]} />)
    const ref = getByTestId('comm.message.r.replyTo')
    expect(ref.getAttribute('data-reply-to')).toBe('p')
    expect(ref.textContent).toContain('Antwort auf')
    expect(getByTestId('comm.message.r').getAttribute('data-reply-depth')).toBe('1')
  })

  it('★ a top-level message has NO reply reference and depth 0', () => {
    const { getByTestId, queryByTestId } = render(<CommPanel {...base} messages={[dm('p'), dm('r', { inReplyTo: 'p' })]} />)
    expect(queryByTestId('comm.message.p.replyTo')).toBeNull()
    expect(getByTestId('comm.message.p').getAttribute('data-reply-depth')).toBe('0')
  })

  it('★ inReplyTo to an ABSENT parent → NO reply reference, depth 0 (no fabricated thread to an unseen message)', () => {
    // MUT: render a reply reference for an unknown parent → fabricates a thread → reds.
    const { getByTestId, queryByTestId } = render(<CommPanel {...base} messages={[dm('o', { inReplyTo: 'ghost' })]} />)
    expect(queryByTestId('comm.message.o.replyTo')).toBeNull()
    expect(getByTestId('comm.message.o').getAttribute('data-reply-depth')).toBe('0')
  })
})
