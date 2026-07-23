// @vitest-environment jsdom
// CYP-814 Batch-1 C1 — the comm-status class is APPLIED per connection state on the rendered element. commBannerCss.test.ts
// pins the CSS rule bodies (offline=warn, revoked=error) but never renders, and CommPanel.render.test.tsx asserts the
// applied class ONLY for `revoked`. So a mutation like `comm-status-${connection==='offline'?'revoked':connection}` would
// paint a reconnectable OFFLINE blip with the TERMINAL-revoke red tone and survive. This pins that each state emits its own
// class, and — the load-bearing half — that a non-revoked state never wears `comm-status-revoked`.
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

describe('CYP-814 C1 — CommPanel applies comm-status-{connection} per state; offline never wears the terminal revoked class', () => {
  for (const state of ['live', 'connecting', 'offline', 'revoked'] as const) {
    it(`★ connection="${state}" → the element carries comm-status-${state}`, () => {
      const { getByTestId } = render(<CommPanel {...base} connection={state} />)
      expect(getByTestId('comm-status').className).toContain(`comm-status-${state}`)
    })
  }

  it('★ a reconnectable OFFLINE state must NOT carry comm-status-revoked (the terminal-red tone) — the collapse mutation REDs', () => {
    const { getByTestId } = render(<CommPanel {...base} connection="offline" />)
    expect(getByTestId('comm-status').className).not.toContain('comm-status-revoked')
  })
})
