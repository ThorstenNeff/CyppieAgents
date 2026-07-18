// @vitest-environment jsdom
// CYP-705 — render teeth for unread-of-record in the Comm panel (UIUX2 spec §9 at 82e3680b — three states).
// Drives the REAL panel. The ★ ② teeth were INVERTED after UIUX2's UX-QA: the first version asserted that unknown
// and confirmed-read looked identical, which pinned the absence-of-signal defect instead of catching it.
import { describe, it, expect, beforeEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CommPanel } from './CommPanel'
import { READ_STATE_UNAVAILABLE, type ReadState } from './unreadModel'
import type { Channel, Message1 } from '../types/generated/contract'

const CHANNELS: readonly Channel[] = [
  { id: 'po-frontend', name: 'PO ↔ Frontend', kind: 'DIRECT', members: [] },
  { id: 'po-backend', name: 'PO ↔ Backend', kind: 'DIRECT', members: [] },
]
const MESSAGES: readonly Message1[] = [
  { id: 'm1', channelId: 'po-frontend', from: 'po', body: 'eins', ts: 0 },
  { id: 'm2', channelId: 'po-frontend', from: 'po', body: 'zwei', ts: 0 },
]

const renderPanel = (readState: ReadState, unreadDividerIndex: number | null = null) =>
  render(
    <CommPanel
      channels={CHANNELS}
      selectedChannelId="po-frontend"
      onSelectChannel={() => undefined}
      messages={MESSAGES}
      senderRole={() => null}
      readState={readState}
      unreadDividerIndex={unreadDividerIndex}
      connection="live"
      canWrite={true}
      sendError={null}
      onSend={() => undefined}
      historySize={() => 0}
    />,
  )

const available = (channels: Record<string, { unreadCount: number; lastReadSeq: number | null }>): ReadState => ({
  kind: 'available',
  channels,
})

/** Every word the panel renders — used to prove no all-clear claim appears anywhere. */
const allText = (c: HTMLElement) => (c.textContent ?? '').toLowerCase()

beforeEach(cleanup)

describe('CYP-705 §9 — unread badge + divider in the Comm panel', () => {
  it('① a server count renders a badge with the count TEXT and an aria-label', () => {
    const { container, getByTestId } = renderPanel(
      available({ 'po-frontend': { unreadCount: 3, lastReadSeq: 10 }, 'po-backend': { unreadCount: 0, lastReadSeq: 4 } }),
    )
    const badge = getByTestId('comm.channel.po-frontend.unreadBadge')
    expect(badge.textContent).toBe('3')
    expect(badge.getAttribute('aria-label')).toBe('3 ungelesen in PO ↔ Frontend')
    // a server-confirmed zero is legitimately silent
    expect(container.querySelector('[data-testid="comm.channel.po-backend.unreadBadge"]')).toBeNull()
  })

  it('★ ② UNKNOWN renders VISIBLY — never as silence, never as an all-clear', () => {
    // The defect: an unavailable read state used to render nothing, and on a channel list nothing reads as
    // "all clear". Unknown must be SEEN.
    const { container, getByTestId } = renderPanel(READ_STATE_UNAVAILABLE)
    const unknown = getByTestId('comm.channel.po-frontend.unreadUnknown')
    expect(unknown.textContent).toBe('•')
    expect(unknown.getAttribute('aria-label')).toBe('Ungelesen-Status unbekannt')
    expect(container.querySelectorAll('[data-testid$=".unreadUnknown"]')).toHaveLength(2) // every channel, not one
    expect(container.querySelectorAll('[data-testid$=".unreadBadge"]')).toHaveLength(0) // and no invented count
    for (const claim of ['alles gelesen', 'all read', 'keine ungelesen', '0 ungelesen']) {
      expect(allText(container)).not.toContain(claim)
    }
  })

  it('★ ② UNKNOWN and confirmed-read are VISUALLY DISTINGUISHABLE, not the same silence', () => {
    // the core of the UX-QA finding: these two must not produce the same pixels.
    const unknownText = renderPanel(READ_STATE_UNAVAILABLE).container.textContent
    cleanup()
    const readState = available({ 'po-frontend': { unreadCount: 0, lastReadSeq: 1 }, 'po-backend': { unreadCount: 0, lastReadSeq: 1 } })
    const { container: readContainer } = renderPanel(readState)
    expect(readContainer.textContent).not.toBe(unknownText) // different — silence only for CONFIRMED read
    expect(readContainer.querySelectorAll('[data-testid$=".unreadUnknown"]')).toHaveLength(0)
    expect(readContainer.querySelectorAll('[data-testid$=".unreadBadge"]')).toHaveLength(0)
  })

  it('★ a channel missing from an available response still shows the unknown marker (§2 encoding)', () => {
    // po-backend is simply absent from the response — that is "never told", not "read".
    const { container, getByTestId } = renderPanel(available({ 'po-frontend': { unreadCount: 2, lastReadSeq: 4 } }))
    expect(getByTestId('comm.channel.po-frontend.unreadBadge').textContent).toBe('2')
    expect(getByTestId('comm.channel.po-backend.unreadUnknown').textContent).toBe('•')
    expect(container.querySelector('[data-testid="comm.channel.po-frontend.unreadUnknown"]')).toBeNull()
  })

  it('③ the divider renders at the given boundary index, and only then', () => {
    const state = available({ 'po-frontend': { unreadCount: 1, lastReadSeq: 1 } })
    const { getByTestId, container } = renderPanel(state, 1)
    const divider = getByTestId('comm.unread.divider')
    expect(divider.textContent).toBe('Neu')
    // it sits BEFORE the second message, not at the end
    const rows = Array.from(container.querySelectorAll('li'))
    expect(rows.findIndex((r) => r.dataset.testid === 'comm.unread.divider')).toBe(1)
    expect(rows[2]?.dataset.testid).toBe('comm.message.m2')
  })

  it('★ ③ no cursor → no divider (never a guessed line)', () => {
    expect(renderPanel(READ_STATE_UNAVAILABLE, null).container.querySelector('[data-testid="comm.unread.divider"]')).toBeNull()
    cleanup()
    const state = available({ 'po-frontend': { unreadCount: 2, lastReadSeq: null } })
    expect(renderPanel(state, null).container.querySelector('[data-testid="comm.unread.divider"]')).toBeNull()
  })

  it('★ ⑤ colour is never the sole carrier — the badge has text and a label, not just a tint', () => {
    const { getByTestId } = renderPanel(available({ 'po-frontend': { unreadCount: 7, lastReadSeq: 2 } }))
    const badge = getByTestId('comm.channel.po-frontend.unreadBadge')
    expect(badge.textContent?.trim()).toBe('7') // a screen reader / greyscale user still gets the count
    expect(badge.getAttribute('aria-label')).toContain('7 ungelesen')
  })

  it('with NO read state passed at all, every channel is unknown — the honest default, not a silent all-clear', () => {
    const { container } = render(
      <CommPanel
        channels={CHANNELS}
        selectedChannelId="po-frontend"
        onSelectChannel={() => undefined}
        messages={MESSAGES}
        senderRole={() => null}
        connection="live"
        canWrite={true}
        sendError={null}
        onSend={() => undefined}
        historySize={() => 0}
      />,
    )
    expect(container.querySelectorAll('[data-testid$=".unreadBadge"]')).toHaveLength(0)
    expect(container.querySelectorAll('[data-testid$=".unreadUnknown"]')).toHaveLength(2) // visible, not silent
    expect(container.querySelector('[data-testid="comm.unread.divider"]')).toBeNull()
  })
})
