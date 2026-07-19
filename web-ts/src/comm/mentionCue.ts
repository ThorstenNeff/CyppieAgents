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
// CYP-744: the mention rule now lives on the SERVER. This cue no longer parses anything — it reads the resolved
// `mentions` spans off the DeliveredMessage envelope. Fail-closed resolution (unloaded/failed roster ⇒ no
// mention) is now the server's job, proven bit-exact against the old client rule by the parity oracle; here a
// message simply either carries spans or it does not.
//
// VIEWER-INDEPENDENT: a span says "@agent was addressed", the same for every viewer. It never encodes who the
// viewer is, so this carries no human identity. "YOUR mentions" would need that and is deliberately out of scope.
import type { DeliveredMessage } from '../types/generated/contract'

/**
 * Does this channel carry at least one server-resolved `@agent` mention among its LOADED messages?
 *
 * `false` means "none found in what we have" — never "none exist". Callers must render the positive only.
 */
export function channelHasMention(delivered: readonly DeliveredMessage[]): boolean {
  return delivered.some((d) => (d.mentions?.length ?? 0) > 0)
}
