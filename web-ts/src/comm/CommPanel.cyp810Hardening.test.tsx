// @vitest-environment jsdom
// CYP-810 G-a1 — CommPanel connection-status TEXT identity. Only `revoked` was pinned (CommPanel.render.test.tsx);
// live/connecting/offline were asserted NOWHERE → swapping live↔connecting (a connected panel displays "Verbinde…")
// survived. This pins each state's status text so a state↔text swap reddens. (Copy-pinned: re-point if the strings
// legitimately change — prove-red is the swap mutation, not current status.)
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [{ id: 'po-frontend', name: 'PO ↔ Frontend', kind: 'DIRECT', members: ['po', 'frontend'] }]
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
const EXPECTED: Record<'live' | 'connecting' | 'offline', string> = {
  live: 'Verbunden',
  connecting: 'Verbinde…',
  offline: 'Offline — Neuverbindung…',
}

describe('CYP-810 G-a1 — CommPanel connection-status text IDENTITY (a live panel never says "connecting")', () => {
  for (const state of ['live', 'connecting', 'offline'] as const) {
    it(`★ connection="${state}" renders ITS status text (a live↔connecting swap REDs)`, () => {
      const { getByTestId } = render(<CommPanel {...base} connection={state} />)
      expect(getByTestId('comm-status').textContent?.trim()).toBe(EXPECTED[state])
    })
  }

  it('the three transient states render DISTINCT text (non-vacuity: identity, not a shared string)', () => {
    const texts = (['live', 'connecting', 'offline'] as const).map((state) => {
      cleanup()
      return render(<CommPanel {...base} connection={state} />).getByTestId('comm-status').textContent?.trim()
    })
    expect(new Set(texts).size).toBe(3)
  })
})
