// @vitest-environment jsdom
// CYP-810 #1 + #2 (EventLogView) — my scoping + Dev5's render-lens:
//   #1 the live/paused STATE GLYPHS (● / ⏸) + the `live = caughtUp && !paused` guard + the `trimmed>0` disclosure were
//      untested (no render passed paused/trimmed). A paused-but-caught-up tail showing "● Live" (spec §5.6 violation),
//      or a silently-dropped `trimmed` disclosure (§5.2 "no silent caps"), would survive.
//   #2 GLYPH COLLISION: the gap-row and error-severity BOTH emit `⚠` (severityGlyph('error')='⚠'). The glyph alone
//      cannot distinguish "a hole in the log" from "an error event" → the distinction MUST be carried by the tone +
//      namespace: the gap-row is WARN-amber (`.event-gap` = warn-container) in its OWN `event-gap`/`event.gap.*`
//      namespace, NOT error-red, NOT `event-sev-error`. That tone was unpinned.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { EventLogView } from './EventLogView'
import type { EventSurrogate } from '../types/generated/contract'

afterEach(cleanup)
const ev = (id: string, seq: number, over: Partial<EventSurrogate> = {}): EventSurrogate => ({
  id, seq, ts: seq, agentId: 'backend', projectId: 'p', type: 'agent.activity', severity: 'info', ...over,
})

describe('CYP-810 #1 — EventLogView live/paused state: a frozen tail is never shown as "live" (§5.6)', () => {
  it('★ caughtUp + PAUSED → the PAUSED indicator, NEVER the live one (mutation drop `!paused` REDs)', () => {
    const { queryByTestId } = render(<EventLogView events={[ev('a', 1)]} caughtUp paused />)
    expect(queryByTestId('event-log-paused')).not.toBeNull()
    expect(queryByTestId('event-log-live')).toBeNull() // a frozen view must NOT claim to be live
  })

  it('★ the state GLYPHS are ● (live) and ⏸ (paused) — pinned so a glyph drift/swap REDs', () => {
    const live = render(<EventLogView events={[ev('a', 1)]} caughtUp />)
    expect(live.getByTestId('event-log-live').textContent).toContain('●')
    cleanup()
    const paused = render(<EventLogView events={[ev('a', 1)]} caughtUp paused />)
    expect(paused.getByTestId('event-log-paused').textContent).toContain('⏸')
  })

  it('★ trimmed>0 is DISCLOSED (no silent cap, §5.2); trimmed=0 shows nothing (non-vacuity)', () => {
    const trimmed = render(<EventLogView events={[ev('a', 1)]} caughtUp trimmed={5} />)
    const marker = trimmed.getByTestId('event-log-trimmed')
    expect(marker.textContent).toContain('⤒')
    expect(marker.textContent).toContain('5')
    cleanup()
    const none = render(<EventLogView events={[ev('a', 1)]} caughtUp trimmed={0} />)
    expect(none.queryByTestId('event-log-trimmed')).toBeNull()
  })
})

describe('CYP-810 #2 — the ⚠ glyph COLLISION: a gap-row is a WARN caution, never masquerading as an error-severity event', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  const gapToken = css.match(/\.event-gap\s*\{[^}]*?background:\s*var\(--md-sys-color-([a-z-]+)\)/)?.[1] ?? null

  it('★ a sequence gap renders its OWN warn namespace (event-gap / event.gap.*), never event-sev-error', () => {
    // seq 1 then seq 3 → a 1-event hole at seq 2 → a gap row.
    const { getByTestId } = render(<EventLogView events={[ev('a', 1), ev('c', 3)]} caughtUp />)
    const gap = getByTestId('event.gap.1')
    expect(gap.textContent).toContain('⚠') // shares the glyph with error-severity...
    expect(gap.textContent).toContain('Lücke') // ...but the TEXT says "a hole in the log", not an error
    expect(gap.className).toContain('event-gap')
    expect(gap.className).not.toContain('event-sev-error') // never masquerades as an error-severity row
  })

  it('★ the gap-row TONE is WARN-amber (warn-container), NOT error-red — the collision is resolved by tone [CSS, jsdom-blind]', () => {
    expect(gapToken).toBe('warn-container')
    expect(gapToken).not.toBe('error-container') // a hole in the log is a caution, not a crash
  })
})
