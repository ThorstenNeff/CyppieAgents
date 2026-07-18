// @vitest-environment jsdom
// CYP-705 — render teeth for unread-of-record in the Comm panel (UIUX2 spec §9, 25cc93ea). Drives the REAL panel.
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

const available = (channels: Record<string, { unread: number; lastReadSeq: number | null }>): ReadState => ({
  kind: 'available',
  channels,
})

/** Every word the panel renders — used to prove no all-clear claim appears anywhere. */
const allText = (c: HTMLElement) => (c.textContent ?? '').toLowerCase()

beforeEach(cleanup)

describe('CYP-705 §9 — unread badge + divider in the Comm panel', () => {
  it('① a server count renders a badge with the count TEXT and an aria-label', () => {
    const { container, getByTestId } = renderPanel(
      available({ 'po-frontend': { unread: 3, lastReadSeq: 10 }, 'po-backend': { unread: 0, lastReadSeq: 4 } }),
    )
    const badge = getByTestId('comm.channel.po-frontend.unreadBadge')
    expect(badge.textContent).toBe('3')
    expect(badge.getAttribute('aria-label')).toBe('3 ungelesen in PO ↔ Frontend')
    // a server-confirmed zero is legitimately silent
    expect(container.querySelector('[data-testid="comm.channel.po-backend.unreadBadge"]')).toBeNull()
  })

  it('★ ② degraded: an unavailable read state shows NO badge and makes NO all-clear claim', () => {
    // the honesty boundary — "no badge" must mean "unknown", and nothing on screen may say otherwise.
    const { container } = renderPanel(READ_STATE_UNAVAILABLE)
    expect(container.querySelectorAll('[data-testid$=".unreadBadge"]')).toHaveLength(0)
    for (const claim of ['alles gelesen', 'all read', 'keine ungelesen', '0 ungelesen', 'gelesen ✓', '✓']) {
      expect(allText(container)).not.toContain(claim)
    }
    // and the panel still works — the degraded path is silent, not broken
    expect(container.querySelector('[data-testid="comm.channel.po-frontend"]')).toBeTruthy()
    expect(container.querySelector('[data-testid="comm.message.m1"]')).toBeTruthy()
  })

  it('★ ② the degraded panel is indistinguishable from all-zero in PIXELS but never asserts zero', () => {
    // both render no badge (present-only). The test that matters is that neither prints a reassuring claim —
    // if a future edit adds "0" chips to the zero case, it must NOT also appear in the unknown case.
    const unknown = renderPanel(READ_STATE_UNAVAILABLE).container.textContent
    cleanup()
    const zero = renderPanel(available({ 'po-frontend': { unread: 0, lastReadSeq: 1 } })).container.textContent
    expect(unknown).toBe(zero) // today: identical, both silent
    expect(allText(document.body)).not.toContain('gelesen') // neither claims read-ness
  })

  it('③ the divider renders at the given boundary index, and only then', () => {
    const state = available({ 'po-frontend': { unread: 1, lastReadSeq: 1 } })
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
    const state = available({ 'po-frontend': { unread: 2, lastReadSeq: null } })
    expect(renderPanel(state, null).container.querySelector('[data-testid="comm.unread.divider"]')).toBeNull()
  })

  it('★ ⑤ colour is never the sole carrier — the badge has text and a label, not just a tint', () => {
    const { getByTestId } = renderPanel(available({ 'po-frontend': { unread: 7, lastReadSeq: 2 } }))
    const badge = getByTestId('comm.channel.po-frontend.unreadBadge')
    expect(badge.textContent?.trim()).toBe('7') // a screen reader / greyscale user still gets the count
    expect(badge.getAttribute('aria-label')).toContain('7 ungelesen')
  })

  it('the badge defaults to absent when no read state is passed at all (fail-closed by omission)', () => {
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
    expect(container.querySelector('[data-testid="comm.unread.divider"]')).toBeNull()
  })
})
