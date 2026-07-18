// CYP-704 (Epic CYP-703) — mention resolution: turn a message body into text/mention segments so an
// addressed-to-a-human message becomes VISIBLE in the client.
//
// THE LOAD-BEARING PROPERTY IS FAIL-CLOSED RESOLUTION. The protocol carries NO mention field (measured against
// `contract/openapi.json` + `asyncapi.json` on develop fa491eed: `Message = {id, channelId, from, body, ts,
// meta{inReplyTo,kind}, projectId}` — zero mention/recipient/notify affordance). A mention therefore has to be
// DERIVED from `body`, which is untrusted, sender-controlled content. So the rule is:
//
//   a `@token` becomes a mention ONLY if it resolves against the roster the CALLER controls — never otherwise.
//
// An unknown `@foo` stays plain text. Without that, any sender could manufacture the visual cue of "this is
// addressed to you" for an arbitrary name by typing it, which is exactly the failure this feature must not have.
// The roster is passed in (from `/api/agents` / `/api/workspace/members`); this module never infers identity from
// the body it is parsing.
//
// ADVISORY BY CONSTRUCTION (the CYP-704 AC hook): there is deliberately NO delivery or read state anywhere in this
// model — no `delivered`, no `seen`, no `notifiedAt`. A mention is a HINT that someone was addressed, never a
// guarantee that they received or read it. The absence is the enforcement: a UI built on these types cannot render
// a delivery claim, because it has no field from which to invent one. Do not add one here.
//
// SYNTAX IS A PARAMETER, NOT A DECISION. UIUX2's UX spec fixes the user-visible token form (`@agentId` vs
// `@displayName`) and is not delivered yet. Rather than guess it and rework the model, the syntax is injected:
// `PROVISIONAL_SYNTAX` is a placeholder marked as such, and the security semantics above hold for ANY syntax.
// Swapping the syntax must not require touching the resolution logic — that separation is the point.

/** A roster entry the CALLER vouches for. Identity comes from the server, never from a parsed body. */
export interface RosterEntry {
  readonly id: string
  readonly displayName: string
}

/** A resolved piece of a body. A `mention` ALWAYS carries the roster id it resolved to — never a raw token. */
export type MessageSegment =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'mention'; readonly text: string; readonly id: string }

export interface MentionSyntax {
  /**
   * Global regex over the body. Group 1 = the leading boundary (kept as text), group 2 = the bare token.
   * The boundary group is what keeps `mail@example.com` from reading as a mention of `example`.
   */
  readonly pattern: RegExp
  /** Which roster keys a token may match. Case-insensitive compare; a key that is blank never matches. */
  readonly keysOf: (entry: RosterEntry) => readonly string[]
}

/**
 * PROVISIONAL — placeholder until UIUX2's spec lands (CYP-704). Matches `@token` at a word boundary and resolves
 * against the roster ID only. Deliberately conservative: it is easier to widen a syntax later than to withdraw
 * mention styling that a body could already trigger.
 */
export const PROVISIONAL_SYNTAX: MentionSyntax = {
  pattern: /(^|[\s(\[{"'„«»—–-])@([A-Za-z0-9._-]{1,64})/g,
  keysOf: (e) => [e.id],
}

/**
 * Split `body` into text/mention segments. Only roster-resolvable tokens become mentions (fail-closed); everything
 * else — including an unknown `@foo` — stays text. Adjacent text is merged so the output is stable to render.
 */
export function resolveMentions(
  body: string,
  roster: readonly RosterEntry[],
  syntax: MentionSyntax = PROVISIONAL_SYNTAX,
): readonly MessageSegment[] {
  // key → id, built from the CONTROLLED roster. Blank keys are dropped so an empty id can never swallow a token.
  const byKey = new Map<string, string>()
  for (const entry of roster) {
    for (const key of syntax.keysOf(entry)) {
      const k = key.trim().toLowerCase()
      if (k !== '') byKey.set(k, entry.id)
    }
  }

  const out: MessageSegment[] = []
  let cursor = 0
  const pushText = (text: string) => {
    if (text === '') return
    const last = out[out.length - 1]
    if (last !== undefined && last.kind === 'text') out[out.length - 1] = { kind: 'text', text: last.text + text }
    else out.push({ kind: 'text', text })
  }

  // fresh regex per call — a shared global regex carries `lastIndex` across calls and would skip matches.
  const re = new RegExp(syntax.pattern.source, syntax.pattern.flags.includes('g') ? syntax.pattern.flags : `${syntax.pattern.flags}g`)
  for (let m = re.exec(body); m !== null; m = re.exec(body)) {
    const [whole, boundary = '', token = ''] = m
    const id = byKey.get(token.toLowerCase())
    if (id === undefined) continue // unknown token → falls through as text (fail-closed)
    pushText(body.slice(cursor, m.index) + boundary)
    out.push({ kind: 'mention', text: `@${token}`, id })
    cursor = m.index + whole.length
  }
  pushText(body.slice(cursor))
  return out
}

/** The roster ids a body addresses, de-duplicated, in first-appearance order. Advisory — see the header. */
export function mentionedIds(segments: readonly MessageSegment[]): readonly string[] {
  // NB: no variable here is named `seen` — the advisory-state guard in the tests scans for exactly that vocabulary,
  // and a local shadowing it would train the team to loosen the guard. Cheaper to pick another name.
  const ids = new Set<string>()
  for (const s of segments) if (s.kind === 'mention') ids.add(s.id)
  return [...ids]
}
