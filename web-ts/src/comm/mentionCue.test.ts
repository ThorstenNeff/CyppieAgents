// CYP-740 — teeth for the channel-level @agent mention cue (UIUX2 spec adcec7ac §3).
import { describe, it, expect } from 'vitest'
import { channelHasMention } from './mentionCue'
import type { Message1 } from '../types/generated/contract'

const msg = (body: string, i = 0): Message1 => ({ id: `m${i}`, channelId: 'c', from: 'po', body, ts: 0 })
const ROSTER = ['frontend', 'po'] as const

describe('CYP-740 — the cue reports a positive it can see, and never claims a negative', () => {
  it('a channel carrying a roster-resolved @agent mention is detected (non-vacuous control)', () => {
    expect(channelHasMention([msg('hi'), msg('bitte @frontend schauen', 1)], ROSTER)).toBe(true)
  })

  it('★ ③.1 fail-closed roster: no roster ⇒ no detection, never a cue from unresolved data', () => {
    // Same rule as the inline chips (CYP-704): without a roster we cannot tell an agent from a typo.
    expect(channelHasMention([msg('bitte @frontend schauen')], [])).toBe(false)
  })

  it('★ ③.4 non-vacuum: an UNKNOWN @token is not a mention — the cue inherits fail-closed resolution', () => {
    expect(channelHasMention([msg('bitte @nobody schauen')], ROSTER)).toBe(false)
  })

  it('★ ③.4 a channel with no loaded messages yields false — which callers must render as SILENCE', () => {
    // `false` here means "none found in what we hold", NOT "none exist". The prohibition lives at the render
    // site (present-only): this function cannot distinguish the two, which is precisely why the UI may never
    // turn its `false` into an affirmative "no mentions".
    expect(channelHasMention([], ROSTER)).toBe(false)
  })

  it('★ ③.3 viewer-independent: the same messages give the same answer regardless of who asks', () => {
    // There is no viewer parameter at all — the signature cannot express "me", so "YOUR mentions" is
    // unreachable from here rather than merely unimplemented.
    const messages = [msg('@po und @frontend')]
    expect(channelHasMention(messages, ROSTER)).toBe(true)
    expect(channelHasMention(messages, ['frontend'])).toBe(true) // a different roster, still no viewer notion
  })

  it('an email-shaped body does not trip the cue (inherits the sigil boundary)', () => {
    expect(channelHasMention([msg('schreib an mail@frontend')], ROSTER)).toBe(false)
  })

  it('a mention inside code does not trip the cue (inherits code-exemption)', () => {
    expect(channelHasMention([msg('nimm `@frontend` als Beispiel')], ROSTER)).toBe(false)
  })
})
