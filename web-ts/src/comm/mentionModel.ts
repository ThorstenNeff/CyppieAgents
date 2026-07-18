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

/**
 * `@` counts as a mention sigil at string start or when the preceding character is NOT an id character. Mirror of
 * the end-boundary rule below, and the same rule the Phase-2 server applies.
 *
 * Requiring *whitespace* was too strict (Tester2 F2): `(@dev5)`, `[@dev5]` and `"@dev5"` are ordinary ways to write
 * a mention and silently produced nothing. Keying on "not an id character" admits those while keeping the email
 * protection intact — in `mail@dev5` the preceding `l` IS an id character, so it is still no mention.
 */
function isSigilBoundary(body: string, at: number): boolean {
  return at === 0 || !ID_CHAR.test(body[at - 1] ?? '')
}

/** The id alphabet — `AgentMgmtGuard.SAFE_ID` is `^[a-zA-Z0-9_-]+$`, so `_` and `-` are part of an id, not breaks. */
const ID_CHAR = /[A-Za-z0-9_-]/

// CODE IS EXEMPT, QUOTES ARE NOT (the rule shared with the Phase-2 server resolver — both sides must recognise the
// same thing or the phase boundary drifts: highlight in Phase 1, no notify in Phase 2). `@frontend` inside code is
// being SHOWN, not addressed — a snippet quoting a handle must not read as summoning that person. A QUOTED line
// (`> …`) is the opposite: quoting someone who addressed you is still addressing, so quotes stay included.
const FENCED = /```[\s\S]*?(?:```|$)/g
const INLINE = /`[^`\n]*`?/g

/** Positions covered by fenced or inline code. Unterminated markers exempt to the end of block/line — fail-closed. */
function codeMask(body: string): readonly boolean[] {
  const mask = new Array<boolean>(body.length).fill(false)
  const cover = (from: number, to: number) => {
    for (let i = from; i < to; i++) mask[i] = true
  }
  for (const m of body.matchAll(FENCED)) cover(m.index, m.index + m[0].length)
  // inline only OUTSIDE fenced regions — a backtick inside a fence is content, not a delimiter.
  for (const m of body.matchAll(INLINE)) {
    if (mask[m.index] !== true) cover(m.index, m.index + m[0].length)
  }
  return mask
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

  const inCode = codeMask(body)
  for (let i = 0; i < body.length; ) {
    if (body[i] !== '@' || !isSigilBoundary(body, i) || inCode[i] === true) {
      pending += body[i]
      i += 1
      continue
    }
    const rest = body.slice(i + 1).toLowerCase()
    // The match must be BOUNDARY-TERMINATED: the next character must end the string or be a non-id character
    // (`[^A-Za-z0-9_-]`). Without this, `@frontend-dev` with only `frontend` on the roster would highlight
    // `frontend` — pointing at an agent the sender did not mean, since `-` is itself a legal id character. Better
    // no mention than a confident one aimed at the wrong person. Mirrors the Phase-2 server rule exactly.
    const hit = ids.find((id) => {
      const lower = id.toLowerCase()
      return rest.startsWith(lower) && !ID_CHAR.test(rest[lower.length] ?? '')
    })
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
