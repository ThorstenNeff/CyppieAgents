// @vitest-environment jsdom
// CYP-704 Phase 1 — render teeth for mention DISPLAY in the timeline (UIUX2 spec §8, frozen 834ecfab).
// These drive the REAL CommPanel, not a stand-in, so a chip that never reaches the DOM fails here.
import { describe, it, expect, beforeEach } from 'vitest'
import { render, cleanup, within } from '@testing-library/react'
import { CommPanel } from './CommPanel'
import type { Channel, Message1 } from '../types/generated/contract'

const CHANNELS: readonly Channel[] = [{ id: 'c1', name: 'Kanal 1', kind: 'GROUP', members: [] }]
const ROSTER = ['frontend', 'po'] as const

const msg = (body: string, id = 'm1'): Message1 => ({ id, channelId: 'c1', from: 'po', body, ts: 0 })

const renderPanel = (body: string, rosterIds: readonly string[] = ROSTER) =>
  render(
    <CommPanel
      channels={CHANNELS}
      selectedChannelId="c1"
      onSelectChannel={() => undefined}
      messages={[msg(body)]}
      senderRole={() => null}
      rosterIds={rosterIds}
      connection="live"
      canWrite={true}
      sendError={null}
      onSend={() => undefined}
      historySize={() => 0}
    />,
  )

const row = (c: HTMLElement) => within(c.querySelector('[data-testid="comm.message.m1"]') as HTMLElement)
const chips = (c: HTMLElement) => Array.from(c.querySelectorAll('[data-testid^="comm.mention."]'))

beforeEach(cleanup)

describe('CYP-704 §8 — mention chips in the timeline', () => {
  it('① a known @id renders ONE chip; the rest of the body survives as text', () => {
    const { container } = renderPanel('bitte @frontend schauen')
    expect(chips(container)).toHaveLength(1)
    expect(chips(container)[0].textContent).toBe('@frontend')
    expect(row(container).getByText(/bitte/)).toBeTruthy()
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('bitte @frontend schauen')
  })

  it('★ ② fail-closed: an unknown @token produces NO chip', () => {
    const { container } = renderPanel('bitte @foo schauen')
    expect(chips(container)).toHaveLength(0)
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('bitte @foo schauen')
  })

  it('★ ③ email-safe: a sigil mid-word produces NO chip', () => {
    // `mail@frontend` is the discriminating case — the token IS a roster id, so only the boundary rule keeps it plain.
    expect(chips(renderPanel('schreib an mail@frontend').container)).toHaveLength(0)
    cleanup()
    expect(chips(renderPanel('schreib an mail@frontend.de').container)).toHaveLength(0)
  })

  it('★ ④ colour is never the sole signal — the chip carries the literal text, not just an accent', () => {
    const { container } = renderPanel('@frontend')
    const chip = chips(container)[0] as HTMLElement
    expect(chip.textContent).toBe('@frontend') // text is the carrier
    expect(chip.style.color).not.toBe('') // accent is secondary, not the only cue
  })

  it('⑤ two mentions → two chips, connecting text stays text', () => {
    const { container } = renderPanel('@po und @frontend')
    expect(chips(container).map((c) => c.textContent)).toEqual(['@po', '@frontend'])
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('@po und @frontend')
  })

  it('⑥ punctuation stays outside the chip', () => {
    const { container } = renderPanel('@frontend!')
    expect(chips(container)[0].textContent).toBe('@frontend')
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('@frontend!')
  })

  it('★ ⑦ an unloaded / failed roster renders plain text — no guessed mention (CYP-288 tie-in)', () => {
    const { container } = renderPanel('@frontend @po', [])
    expect(chips(container)).toHaveLength(0)
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('@frontend @po')
  })

  it('★ renders as TEXT NODES — markup in a body is never interpreted (CYP-456/W9 invariant)', () => {
    const { container } = renderPanel('<img src=x onerror=alert(1)> @frontend')
    expect(container.querySelector('img')).toBeNull() // escaped, not parsed
    expect((container.querySelector('.comm-body') as HTMLElement).textContent).toBe('<img src=x onerror=alert(1)> @frontend')
  })
})
