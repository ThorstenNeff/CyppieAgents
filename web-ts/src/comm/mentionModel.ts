// CYP-704 Phase 1 (Epic CYP-703) — mention DISPLAY: split a message body into text + mention segments so an
// addressed message becomes VISIBLE in the timeline. Built against UIUX2's UX spec, frozen at 834ecfab.
//
// THE LOAD-BEARING PROPERTY IS FAIL-CLOSED ROSTER RESOLUTION. The protocol carries NO mention field (measured on
// develop fa491eed: `Message = {id, channelId, from, body, ts, meta{inReplyTo,kind}, projectId}` — zero
// mention/recipient/notify affordance in either contract), so a mention is DERIVED from `body`, which is untrusted,
// sender-controlled content. Therefore:
//
//   a `@token` gets mention styling ONLY if `token` is a known roster id — otherwise it stays plain text.
//
// The defect to prevent is the FALSE POSITIVE: rendering `@foo` as a mention when nobody is called foo lies about
// someone being addressed. Roster-gating also means an unloaded or FAILED roster yields plain text, never a guessed
// mention on unresolved data (the CYP-288 tie-in — failed load must not become a confident-looking surface).
//
// DISPLAY IS VIEWER-INDEPENDENT: `@frontend` looks the same to every viewer, so there is no "me" concept here.
// Self-mention (@you) is NOTIFY = Phase 2, blocked on a self-identity decision (`AuthMe` is content-free by design,
// CYP-470) and deliberately absent from this model.
//
// ADVISORY BY CONSTRUCTION: there is no delivery or read state anywhere in this model — no `delivered`, no `readAt`.
// A mention is a hint that someone was addressed, never proof they received or read it. The absence is the
// enforcement: a UI built on these types cannot render a delivery claim, having no field to invent one from.
// Guarded by a source scan in the tests. Do not add one here.
//
// PHASE-1 TERMINAL (PL ruling): when the server ships `mentionsYou` + spans it becomes the SINGLE source for both
// display and notify, and this client-side parser is REPLACED, not supplemented. This file is not a permanent home.

/** A resolved piece of a body. A `mention` ALWAYS carries the canonical roster id, never the raw typed token. */
export type MessageSegment =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'mention'; readonly text: string; readonly id: string }

/** `@` counts as a mention sigil only at string start or after whitespace — this is what makes `a@b.com` safe. */
function isSigilBoundary(body: string, at: number): boolean {
  return at === 0 || /\s/.test(body[at - 1] ?? '')
}

/**
 * Split `body` into text/mention segments against the roster the CALLER controls.
 *
 * Resolution is longest-match on the roster ids (spec §3.2: `@frontend!` → id `frontend`, `!` stays text) and
 * case-insensitive (spec §3.3 — people type `@Frontend`). An unresolvable `@token` stays text (fail-closed), and an
 * EMPTY roster resolves nothing at all, which is exactly the not-yet-loaded / load-failed behaviour §2 requires.
 */
export function mentionSegments(body: string, rosterIds: readonly string[]): readonly MessageSegment[] {
  // longest first so `@frontend` prefers `frontend` over a roster that also contains `front`.
  const ids = [...rosterIds].filter((id) => id.trim() !== '').sort((a, b) => b.length - a.length)
  const out: MessageSegment[] = []
  let pending = '' // text accumulated since the last emitted segment
  const flush = () => {
    if (pending !== '') out.push({ kind: 'text', text: pending })
    pending = ''
  }

  for (let i = 0; i < body.length; ) {
    if (body[i] !== '@' || !isSigilBoundary(body, i)) {
      pending += body[i]
      i += 1
      continue
    }
    const rest = body.slice(i + 1).toLowerCase()
    const hit = ids.find((id) => rest.startsWith(id.toLowerCase()))
    if (hit === undefined) {
      pending += body[i] // unknown token → the '@' is ordinary text (fail-closed)
      i += 1
      continue
    }
    flush()
    out.push({ kind: 'mention', text: body.slice(i, i + 1 + hit.length), id: hit })
    i += 1 + hit.length
  }
  flush()
  return out
}

/** The roster ids a body addresses, de-duplicated, in first-appearance order. Advisory — see the header. */
export function mentionedIds(segments: readonly MessageSegment[]): readonly string[] {
  // NB: no variable here is named `seen` — the advisory-state guard scans for exactly that vocabulary, and a local
  // shadowing it would train the team to loosen the guard. Cheaper to pick another name.
  const ids = new Set<string>()
  for (const s of segments) if (s.kind === 'mention') ids.add(s.id)
  return [...ids]
}
