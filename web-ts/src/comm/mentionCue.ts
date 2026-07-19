// CYP-740 (UIUX2 spec adcec7ac) — the channel-level cue: "this channel carries @agent mentions".
//
// The inline chips (CYP-704) are only visible once a channel is OPEN, so a mention in a channel you are not
// looking at is invisible. This surfaces that one fact at the channel button — and nothing more.
//
// ★ PRESENT-ONLY, AND THE ABSENCE IS SILENT. Detection runs over LOADED messages, and the client holds messages
// only for channels it has opened (plus whatever arrived live). So "no cue" can mean "no mentions" OR "we never
// looked" — and those must not be conflated. The rule that keeps this honest is therefore not a third marker but
// a prohibition: the UI may show the positive, and must NEVER affirm the negative. No "0 mentions", no "nothing
// for you here" — silence stays silence, which is the only thing an unlooked-at channel entitles us to say.
//
// FAIL-CLOSED ROSTER: with no roster loaded, `mentionSegments` resolves nothing (CYP-704's own rule), so there is
// no detection and no cue — never a cue derived from unresolved data.
//
// VIEWER-INDEPENDENT: this resolves `@agent` against the roster, exactly as the inline chips do. It never asks who
// the viewer is, so it carries no human identity. "YOUR mentions" would need that and is deliberately out of scope.
import { mentionSegments } from './mentionModel'
import type { Message1 } from '../types/generated/contract'

/**
 * Does this channel carry at least one roster-resolved `@agent` mention among its LOADED messages?
 *
 * `false` means "none found in what we have" — never "none exist". Callers must render the positive only.
 */
export function channelHasMention(messages: readonly Message1[], rosterIds: readonly string[]): boolean {
  if (rosterIds.length === 0) return false // fail-closed: no roster ⇒ no detection (CYP-704 rule)
  return messages.some((m) => mentionSegments(m.body, rosterIds).some((s) => s.kind === 'mention'))
}
