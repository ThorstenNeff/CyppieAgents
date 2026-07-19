// @vitest-environment jsdom
// CYP-744 Phase 2 — render teeth for mention DISPLAY in the timeline, now sourced from SERVER spans (was CYP-704
// client parsing). These drive the REAL CommPanel, not a stand-in, so a chip that never reaches the DOM fails here.
//
// WHAT MOVED: resolution (which @token is a mention) is the SERVER's job now, proven == the old client rule by the
// parity oracle (mentionParityFixtures.test.ts). So the fail-closed cases here assert the OUTPUT shape the server
// produces — an empty `mentions` array → no chip — rather than re-testing resolution. What stays a RENDER concern
// and is pinned here: chips reach the DOM, the body renders losslessly, text-nodes-not-markup, colour-never-sole,
// verbatim token text, and fail-closed slicing on a malformed span.
import { describe, it, expect, beforeEach } from 'vitest'
import { render, cleanup, within } from '@testing-library/react'
import { CommPanel } from './CommPanel'
import type { Channel, DeliveredMessage, MentionSpan } from '../types/generated/contract'

const CHANNELS: readonly Channel[] = [{ id: 'c1', name: 'Kanal 1', kind: 'GROUP', members: [] }]

const deliver = (body: string, mentions: readonly MentionSpan[] = []): DeliveredMessage => ({
  message: { id: 'm1', channelId: 'c1', from: 'po', body, ts: 0 },
  mentions: [...mentions],
})
/** A span for the FIRST occurrence of `token` in `body` — self-checking so the offsets can't silently drift. */
const at = (body: string, token: string, id: string): MentionSpan => {
  const start = body.indexOf(token)
  return { start, end: start + token.length, id }
}

const renderPanel = (body: string, mentions: readonly MentionSpan[] = []) =>
  render(
    <CommPanel
      channels={CHANNELS}
      selectedChannelId="c1"
      onSelectChannel={() => undefined}
      messages={[deliver(body, mentions)]}
      senderRole={() => null}
      connection="live"
      canWrite={true}
      sendError={null}
      onSend={() => undefined}
      historySize={() => 0}
    />,
  )

const row = (c: HTMLElement) => within(c.querySelector('[data-testid="comm.message.m1"]') as HTMLElement)
const chips = (c: HTMLElement) => Array.from(c.querySelectorAll('[data-testid^="comm.mention."]'))
const bodyText = (c: HTMLElement) => (c.querySelector('.comm-body') as HTMLElement).textContent

beforeEach(cleanup)

describe('CYP-744 — mention chips in the timeline render from server spans', () => {
  it('① one span → ONE chip; the rest of the body survives as text', () => {
    const body = 'bitte @frontend schauen'
    const { container } = renderPanel(body, [at(body, '@frontend', 'frontend')])
    expect(chips(container)).toHaveLength(1)
    expect(chips(container)[0].textContent).toBe('@frontend')
    expect(row(container).getByText(/bitte/)).toBeTruthy()
    expect(bodyText(container)).toBe('bitte @frontend schauen')
  })

  it('★ ② fail-closed OUTPUT: the server resolved no mention (empty spans) ⇒ NO chip', () => {
    // `@foo` was not a mention on the server; the envelope arrives with `mentions: []`. The client renders it as
    // plain text — it does not, and cannot, re-derive a chip from the body (the parser is gone from this path).
    const { container } = renderPanel('bitte @foo schauen', [])
    expect(chips(container)).toHaveLength(0)
    expect(bodyText(container)).toBe('bitte @foo schauen')
  })

  it('★ ③ email-shaped body with no span ⇒ NO chip (the server applied the sigil-boundary rule)', () => {
    expect(chips(renderPanel('schreib an mail@frontend').container)).toHaveLength(0)
    cleanup()
    expect(chips(renderPanel('schreib an mail@frontend.de').container)).toHaveLength(0)
  })

  it('★ ④ colour is never the sole signal — the chip carries the literal text, not just an accent', () => {
    const { container } = renderPanel('@frontend', [at('@frontend', '@frontend', 'frontend')])
    const chip = chips(container)[0] as HTMLElement
    expect(chip.textContent).toBe('@frontend') // text is the carrier
    expect(chip.style.color).not.toBe('') // accent is secondary, not the only cue
  })

  it('⑤ two spans → two chips, connecting text stays text', () => {
    const body = '@po und @frontend'
    const { container } = renderPanel(body, [{ start: 0, end: 3, id: 'po' }, at(body, '@frontend', 'frontend')])
    expect(chips(container).map((c) => c.textContent)).toEqual(['@po', '@frontend'])
    expect(bodyText(container)).toBe('@po und @frontend')
  })

  it('⑥ punctuation stays outside the chip (the span ends at the id)', () => {
    const body = '@frontend!'
    const { container } = renderPanel(body, [at(body, '@frontend', 'frontend')])
    expect(chips(container)[0].textContent).toBe('@frontend')
    expect(bodyText(container)).toBe('@frontend!')
  })

  it('★ ⑦ no spans at all ⇒ plain text — nothing guessed when the server sent nothing (CYP-288 tie-in)', () => {
    const { container } = renderPanel('@frontend @po', [])
    expect(chips(container)).toHaveLength(0)
    expect(bodyText(container)).toBe('@frontend @po')
  })

  it('★ the chip text is the token VERBATIM as typed, even when the canonical id differs in case', () => {
    // Old client behaviour preserved: highlight what the sender wrote, colour by the canonical id. The span slices
    // the body, so a lower-case canonical id never rewrites the displayed `@FrontEnd`.
    const body = 'moin @FrontEnd'
    const { container } = renderPanel(body, [at(body, '@FrontEnd', 'frontend')])
    expect(chips(container)[0].textContent).toBe('@FrontEnd')
    expect(bodyText(container)).toBe('moin @FrontEnd')
  })

  it('★ renders as TEXT NODES — markup in a body is never interpreted (CYP-456/W9 invariant)', () => {
    const body = '<img src=x onerror=alert(1)> @frontend'
    const { container } = renderPanel(body, [at(body, '@frontend', 'frontend')])
    expect(container.querySelector('img')).toBeNull() // escaped, not parsed
    expect(bodyText(container)).toBe('<img src=x onerror=alert(1)> @frontend')
  })

  it('★ fail-closed slicing: a MALFORMED span (out of range) renders losslessly as text, no chip, no crash', () => {
    // A span the server should never send, but the render must survive it fail-closed rather than mis-slice or throw:
    // an end past the body length is dropped, and the body still renders whole.
    const body = 'hallo welt'
    const { container } = renderPanel(body, [{ start: 6, end: 999, id: 'frontend' }])
    expect(chips(container)).toHaveLength(0)
    expect(bodyText(container)).toBe('hallo welt')
  })
})
