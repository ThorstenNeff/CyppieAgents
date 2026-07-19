// CYP-744 Phase 2 (Epic CYP-703) — mention DISPLAY from SERVER-AUTHORITATIVE spans. This replaces the CYP-704
// client parser (mentionModel.ts) in the render path: the server now owns the mention rule and ships resolved
// `MentionSpan`s on the DeliveredMessage envelope, proven bit-exact against the old client rule by the parity
// oracle (mentionParityFixtures.test.ts / docs/design/cyp744-mention-parity-fixtures.json). The client no longer
// RESOLVES anything — it only SLICES the body at the offsets the server already computed.
//
// WHY A NEW FILE, NOT AN EDIT OF mentionModel.ts: the two are different jobs. mentionModel.ts PARSES untrusted body
// text against a roster (the rule); this module APPLIES a resolved result. Keeping them apart is what lets the
// gate-2 tooth (noClientMentionCompute.test.ts) assert that NO render/production module still parses mentions —
// mentionModel.ts survives only as the parity reference the oracle regenerates from, imported by tests alone.
//
// OFFSETS ARE UTF-16 CODE UNITS, `end` EXCLUSIVE — the same convention the parity oracle pins (and the reason the
// oracle carries an astral-emoji case: a server counting code POINTS would mis-slice here, and this is where it
// would show). `String.prototype.slice` is UTF-16-indexed, so it matches the server's units by construction.
import type { MentionSpan } from '../types/generated/contract'

/** A resolved piece of a body for rendering. A `mention` carries the canonical roster id (for the accent) and the
 *  VERBATIM typed token (`body.slice(start,end)`, sigil included) — we highlight what the sender wrote, never a
 *  rewritten id. Deliberately its own type, not mentionModel's MessageSegment: the render path imports zero from
 *  the parser. */
export type MentionSegment =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'mention'; readonly text: string; readonly id: string }

/**
 * Split `body` into text/mention segments at the server-supplied spans.
 *
 * FAIL-CLOSED, NOT FAIL-BRITTLE: spans arrive over our own /ws/comm + REST (Zod-validated shape), but a span is
 * still data derived from sender-controlled `body`. A span that is out of range, inverted/empty, or overlaps a
 * prior one is SKIPPED — its region stays plain text rather than producing a mis-aimed chip or throwing. Because
 * the cursor only ever advances, every character of `body` is emitted exactly once: the render is always LOSSLESS
 * (concatenating the segment texts reproduces `body`), which is also what keeps the XSS/text-node invariant intact.
 */
export function applyMentionSpans(body: string, spans: readonly MentionSpan[]): readonly MentionSegment[] {
  const ordered = [...spans].sort((a, b) => a.start - b.start)
  const out: MentionSegment[] = []
  let cursor = 0
  for (const s of ordered) {
    // start<cursor catches overlap AND a negative start; end<=start catches empty/inverted; end>len catches
    // out-of-range. Any of these ⇒ no chip for this span (fail-closed) — better plain text than a wrong mention.
    if (s.start < cursor || s.end <= s.start || s.end > body.length) continue
    if (s.start > cursor) out.push({ kind: 'text', text: body.slice(cursor, s.start) })
    out.push({ kind: 'mention', text: body.slice(s.start, s.end), id: s.id })
    cursor = s.end
  }
  if (cursor < body.length) out.push({ kind: 'text', text: body.slice(cursor) })
  return out
}
