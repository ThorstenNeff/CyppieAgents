// CYP-705 (Epic CYP-703, Achse 2) — unread-of-record per channel. Built against UIUX2's UX spec at 82e3680b
// (the three-state revision; the earlier 25cc93ea two-state version is superseded — see the note below).
//
// §0 IS THE WHOLE POINT: an unread count is only truthful if a SERVER-AUTHORITATIVE, per-principal `lastRead`
// cursor stands behind it. A client-only marker (localStorage/memory) fakes DURABLE certainty — it does not travel
// across sessions or devices, it drifts, and it says "read" where only "scrolled past" happened. So this model has
// no local fallback and cannot acquire one: it takes the read state as INPUT and never derives it.
//
// THREE STATES, NOT TWO — and the reason is a defect this file originally had. The first version treated the
// marker as present-only: unread>0 showed a count, and BOTH "server says zero" and "we have no idea" rendered
// nothing. UIUX2's UX-QA caught it: on a channel list, silence reads as ALL-CLEAR, so rendering UNKNOWN as absence
// is itself the false reassurance — the CYP-288 class at the pixel, not merely in intent. Hence:
//   • unread>0            → count badge
//   • confirmed-read      → nothing (authoritative; we KNOW it is read)
//   • UNKNOWN             → a VISIBLE neutral marker, never silence
// Neutral, never alarming: unknown is undetermined, not an error — over-alarming would be its own dishonesty.
//
// NON-OPTIMISTIC (§3): the display derives ONLY from the server cursor. A local scroll may drive the mark-read
// CALL, but never the badge — it clears on the server's echo of the new cursor, not on the scroll that requested
// it. "Read" means the server durably recorded your cursor, not "you understood it".
//
// SURFACE NOT BUILT YET (§8/§10): Backend2 has PINNED `ChannelReadState{lastReadSeq, unreadCount}` (with `seq`
// exposed and read-receipts deliberately out — read state is self-only), but the endpoint and `ReadStateEvent`
// still need bilateral ratification before the cursor path is wired. Until then every channel is UNKNOWN, which
// is now visibly honest rather than quietly reassuring. Today `Message` carries no `seq` at all (measured on
// develop: {id, channelId, from, body, ts, meta, projectId}), so `firstUnreadIndex` is written against a minimal
// `{seq}` shape rather than the wire type: it stays inert until the cursor exists, instead of guessing a field.

/**
 * The caller's read state. `unavailable` is a FIRST-CLASS state, not a null — "we do not know" has to be
 * representable, or it decays into "nothing unread" at the first careless call site.
 */
export type ReadState =
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'available'; readonly channels: Readonly<Record<string, ChannelReadState>> }

/** Backend2's pinned `:core` shape (2026-07-18): `ChannelReadState{lastReadSeq, unreadCount}`. */
export interface ChannelReadState {
  /** Server-computed count. Own messages are excluded server-side (§9.7) — never counted here. */
  readonly unreadCount: number
  /** Server cursor; `null` = the server has no cursor for this channel yet (never "start of time"). */
  readonly lastReadSeq: number | null
}

/**
 * The three distinct states of §0 — deliberately a closed union, because the whole defect class here is two of
 * them collapsing into one. There is no two-state accessor in this module on purpose: an API that answered
 * "badge or no badge" would let a caller render UNKNOWN as silence, which is the exact bug this replaced.
 */
export type ChannelUnread =
  | { readonly kind: 'unread'; readonly count: number }
  | { readonly kind: 'read' }
  | { readonly kind: 'unknown' }

export const READ_STATE_UNAVAILABLE: ReadState = { kind: 'unavailable' }

/** Whether the read-state SURFACE answered at all (distinct from per-channel unknown — see [channelUnread]). */
export function isReadStateKnown(state: ReadState): boolean {
  return state.kind === 'available'
}

/**
 * Which of the three states a channel is in.
 *
 * The encoding is the one Backend2 pinned (§2): a channel MISSING from the read-state response is UNKNOWN; a
 * channel PRESENT with `unreadCount=0` is confirmed-read. That distinction is the whole hinge — without it,
 * "we never heard about this channel" and "the server says you have read it" become the same pixel.
 *
 * UNKNOWN must be RENDERED VISIBLY by callers (a neutral marker), never as absence: on a channel list, silence
 * reads as all-clear, so staying quiet would be the lie rather than the caution. Neutral, not alarming — unknown
 * is not an error, it is merely undetermined.
 */
export function channelUnread(state: ReadState, channelId: string): ChannelUnread {
  if (state.kind !== 'available') return { kind: 'unknown' } // surface absent → undetermined, NOT all-clear
  const entry = state.channels[channelId]
  if (entry === undefined) return { kind: 'unknown' } // §2: missing channel ⇒ unknown, never an implied zero
  if (entry.unreadCount <= 0) return { kind: 'read' } // server-confirmed → honestly silent
  return { kind: 'unread', count: entry.unreadCount }
}

/**
 * Index of the first message after the read cursor — where the "Neu" divider goes — or `null` for no divider.
 *
 * No cursor (unknown read state, or no cursor for this channel) ⇒ `null`: without server truth there is no honest
 * place to draw the line, so none is drawn. Ordering is `seq`, the server's monotonic key, never `ts` (client
 * clocks are "observed", not authoritative).
 */
export function firstUnreadIndex(
  messages: readonly { readonly seq: number }[],
  state: ReadState,
  channelId: string,
): number | null {
  if (state.kind !== 'available') return null
  const cursor = state.channels[channelId]?.lastReadSeq
  if (cursor === undefined || cursor === null) return null
  const index = messages.findIndex((m) => m.seq > cursor)
  return index === -1 ? null : index
}
