// CYP-740 / CYP-744 — teeth for the channel-level @agent mention cue. The cue now reads the SERVER's resolved
// spans off the DeliveredMessage envelope (resolution moved server-side, proven == the old client rule by the
// parity oracle). This function's remaining job is the HONESTY rule: report a positive it can see, never a negative.
import { describe, it, expect } from 'vitest'
import { channelHasMention } from './mentionCue'
import type { DeliveredMessage } from '../types/generated/contract'

/** An envelope the server resolved to one mention. */
const mentioned = (id = 'm'): DeliveredMessage => ({
  message: { id, channelId: 'c', from: 'po', body: 'bitte @frontend schauen', ts: 0 },
  mentions: [{ start: 6, end: 15, id: 'frontend' }],
})
/** An envelope the server resolved to NO mention (unknown token, email, code, unloaded roster all arrive so). */
const plain = (id = 'm'): DeliveredMessage => ({
  message: { id, channelId: 'c', from: 'po', body: 'nichts hier', ts: 0 },
  mentions: [],
})

describe('CYP-740 — the cue reports a positive it can see, and never claims a negative', () => {
  it('a channel whose envelope carries a span is detected (non-vacuous control)', () => {
    expect(channelHasMention([plain('a'), mentioned('b')])).toBe(true)
  })

  it('★ no span ⇒ no detection: a resolved-to-empty message never lights the cue', () => {
    // Whatever the server\'s reason for finding nothing, an empty `mentions` is NOT a cue — the client never
    // second-guesses it by re-reading the body.
    expect(channelHasMention([plain()])).toBe(false)
  })

  it('★ absent mentions field (older/edge envelope) is treated as no mention, never a guess', () => {
    const noField: DeliveredMessage = { message: { id: 'm', channelId: 'c', from: 'po', body: '@frontend', ts: 0 } }
    expect(channelHasMention([noField])).toBe(false)
  })

  it('★ a channel with no loaded messages yields false — which callers must render as SILENCE', () => {
    // `false` here means "none found in what we hold", NOT "none exist". The prohibition lives at the render site
    // (present-only): this function cannot distinguish the two, which is why the UI may never turn `false` into an
    // affirmative "no mentions".
    expect(channelHasMention([])).toBe(false)
  })

  it('★ viewer-independent: the signature has no viewer parameter — "YOUR mentions" is unreachable, not just unbuilt', () => {
    // A span says "@agent was addressed", identically for everyone. There is nowhere to pass "me", so a
    // viewer-specific cue cannot be expressed from here.
    expect(channelHasMention([mentioned()])).toBe(true)
  })
})
