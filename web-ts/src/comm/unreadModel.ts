// CYP-705 (Epic CYP-703, Achse 2) — unread-of-record per channel. Built against UIUX2's UX spec at 82e3680b and
// Backend2's ratified `:core` contract (`docs/design/cyp705-read-state-contract.md`), now live in develop.
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
// CALL, but never the badge — it clears on the server's echo (`ReadStateEvent` / the POST's 200), not on the
// scroll that requested it. "Read" means the server durably recorded your cursor, not "you understood it".
//
// NAMING: the generated contract exports `ReadState` for the *WS event variant* (`type: "readState"`). The
// client-side surface state below is therefore `UnreadView` — two different things must not share a name in a
// file that imports both.
import type { ChannelReadState, Message1 } from '../types/generated/contract'

export type { ChannelReadState }

/**
 * The caller's view of read state. `unavailable` is a FIRST-CLASS state, not a null — "we do not know" has to be
 * representable, or it decays into "nothing unread" at the first careless call site.
 */
export type UnreadView =
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'available'; readonly channels: Readonly<Record<string, ChannelReadState>> }

/**
 * The three distinct states of §0 — deliberately a closed union, because the whole defect class here is two of
 * them collapsing into one. There is no two-state accessor in this module on purpose: an API that answered
 * "badge or no badge" would let a caller render UNKNOWN as silence, which is the exact bug this replaced.
 */
export type ChannelUnread =
  | { readonly kind: 'unread'; readonly count: number }
  | { readonly kind: 'read' }
  | { readonly kind: 'unknown' }

export const READ_STATE_UNAVAILABLE: UnreadView = { kind: 'unavailable' }

/**
 * Fold the wire list into the view. THE ONE PLACE the §2 presence rule is enforced: the server sends an entry
 * **iff** the principal has a cursor for that channel, so "channel absent from the list" means UNKNOWN. Keeping
 * this in a single function is deliberate — spread across call sites, "absent" quietly becomes "zero" again.
 *
 * A defensive edge check rides along: the contract types `lastReadSeq` as required, but this is untrusted wire
 * data, and a null/absent cursor arriving anyway must not silently become `0`. `seq > null` is `seq > 0` in JS,
 * which would mark EVERY message unread and drop the divider at the top. Such an entry is dropped → the channel
 * stays UNKNOWN, which is the honest answer for "the server said something we cannot interpret".
 */
export function unreadViewFrom(entries: readonly ChannelReadState[]): UnreadView {
  const channels: Record<string, ChannelReadState> = {}
  for (const e of entries) {
    if (typeof e?.channelId !== 'string' || e.channelId === '') continue
    if (typeof e.lastReadSeq !== 'number' || typeof e.unreadCount !== 'number') continue
    channels[e.channelId] = e
  }
  return { kind: 'available', channels }
}

/** Whether the read-state SURFACE answered at all (distinct from per-channel unknown — see [channelUnread]). */
export function isReadStateKnown(view: UnreadView): boolean {
  return view.kind === 'available'
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
export function channelUnread(view: UnreadView, channelId: string): ChannelUnread {
  if (view.kind !== 'available') return { kind: 'unknown' } // surface absent → undetermined, NOT all-clear
  const entry = view.channels[channelId]
  if (entry === undefined) return { kind: 'unknown' } // §2: missing channel ⇒ unknown, never an implied zero
  if (entry.unreadCount <= 0) return { kind: 'read' } // server-confirmed → honestly silent
  return { kind: 'unread', count: entry.unreadCount }
}

/**
 * Does this message carry an authoritative ordering key?
 *
 * `seq` is OPTIONAL on the wire and defaults to `0` in `:core` — that default is load-bearing for decoding older
 * persisted payloads (making it `@Required` would throw `MissingFieldException` on old store JSON and break
 * restart survival). So BOTH `undefined` and `0` mean "no authoritative order": `0` is the unassigned/legacy
 * sentinel, NOT position zero. Treating it as a real position would sort a legacy message ahead of everything and
 * silently misplace the divider — and `undefined > cursor` is `false` in JS, which files it as read just as
 * quietly. Neither failure throws; both simply show the wrong thing.
 */
export function hasAuthoritativeSeq(m: Pick<Message1, 'seq'>): boolean {
  return typeof m.seq === 'number' && m.seq > 0
}

/**
 * Index of the first message after the read cursor — where the "Neu" divider goes — or `null` for no divider.
 *
 * Messages without an authoritative `seq` can never BE the boundary, but they do not suppress it either: with
 * legacy and real messages mixed, the line is still drawn at the first genuine unread one. Fail-closed here means
 * "omit the guessed claim", not "switch the feature off" — a single legacy row silently disabling the divider for
 * a whole channel would itself read as "nothing new", the very defect this ticket is about.
 *
 * Ordering is `seq`, the server's monotonic key, never `ts` (client clocks are observed, not authoritative). The
 * contract guarantees channel reads arrive seq-ascending, so this does not re-sort.
 */
export function firstUnreadIndex(
  messages: readonly Pick<Message1, 'seq'>[],
  view: UnreadView,
  channelId: string,
): number | null {
  if (view.kind !== 'available') return null
  const cursor = view.channels[channelId]?.lastReadSeq
  if (typeof cursor !== 'number') return null
  const index = messages.findIndex((m) => hasAuthoritativeSeq(m) && (m.seq as number) > cursor)
  return index === -1 ? null : index
}

/**
 * The `upToSeq` to send for mark-read: the highest AUTHORITATIVE seq present, or `null` for "do not call".
 *
 * Only authoritative seqs count — sending `upToSeq: 0` would be a no-op server-side (`max(existing, 0)`) but it
 * is a request that claims to have read something, and claiming is what this feature must not do casually.
 */
export function markReadUpTo(messages: readonly Pick<Message1, 'seq'>[]): number | null {
  let highest: number | null = null
  for (const m of messages) {
    if (!hasAuthoritativeSeq(m)) continue
    const seq = m.seq as number
    if (highest === null || seq > highest) highest = seq
  }
  return highest
}
