// CYP-705 (Epic CYP-703, Achse 2) — unread-of-record per channel. Built against UIUX2's UX spec at 25cc93ea.
//
// §0 IS THE WHOLE POINT: an unread badge is only truthful if a SERVER-AUTHORITATIVE, per-principal `lastRead`
// cursor stands behind it. A client-only marker (localStorage/memory) fakes DURABLE certainty — it does not travel
// across sessions or devices, it drifts, and it says "read" where only "scrolled past" happened. So this model has
// no local fallback and cannot acquire one: it takes the read state as INPUT and never derives it.
//
// THE HARD PART IS THE ABSENCE. Without a server cursor there is no badge — but the absence must not read as
// "all clear". Unknown ≠ zero. `seen=0` confirmed BY THE SERVER is an honest "read" (a legitimate empty); an
// unavailable read-state is *unknown*, and rendering "✓ all read" over it would be the CYP-288 defect one level
// up: presenting a missing answer as a reassuring one. Both cases render nothing (present-only), which is exactly
// why the two must stay DISTINGUISHABLE in the model — a caller that ever grows a read column has to be able to
// say "Status unbekannt" instead of "0", and a test has to be able to tell the two apart.
//
// NON-OPTIMISTIC (§3): the display derives ONLY from the server cursor. A local scroll may drive the mark-read
// CALL, but never the badge — it clears on the server's echo of the new cursor, not on the scroll that requested
// it. "Read" means the server durably recorded your cursor, not "you understood it".
//
// BACKEND SURFACE NOT PINNED YET (§8/§10): Backend2's read-state surface is still being measured, so this file
// deliberately models only what the spec fixes — the shapes below are the CLIENT's view, and the wire mapping is
// wired when the contract is pinned. Today `Message` carries no `seq` at all (measured on develop: {id, channelId,
// from, body, ts, meta, projectId}), so `firstUnreadIndex` is written against a minimal `{seq}` shape rather than
// the wire type: it stays inert until the cursor exists, instead of guessing a field.

/**
 * The caller's read state. `unavailable` is a FIRST-CLASS state, not a null — "we do not know" has to be
 * representable, or it decays into "nothing unread" at the first careless call site.
 */
export type ReadState =
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'available'; readonly channels: Readonly<Record<string, ChannelReadState>> }

export interface ChannelReadState {
  /** Server-computed count. Own messages are excluded server-side (§9.7) — never counted here. */
  readonly unread: number
  /** Server cursor; `null` = the server has no cursor for this channel yet (never "start of time"). */
  readonly lastReadSeq: number | null
}

export const READ_STATE_UNAVAILABLE: ReadState = { kind: 'unavailable' }

/** True only when the server answered. Drives "Status unbekannt" wording — never a rendered "0". */
export function isReadStateKnown(state: ReadState): boolean {
  return state.kind === 'available'
}

/**
 * The badge to show for a channel, or `null` for "render nothing".
 *
 * `null` covers BOTH an honest server-confirmed zero and an unknown read state — present-only, so both are silent.
 * Use [isReadStateKnown] when a surface needs to *say* which one it is; never infer "all read" from `null`.
 */
export function unreadBadge(state: ReadState, channelId: string): { readonly count: number } | null {
  if (state.kind !== 'available') return null // unknown → silent, and NEVER an all-clear
  const entry = state.channels[channelId]
  if (entry === undefined || entry.unread <= 0) return null // server-confirmed read → legitimately empty
  return { count: entry.unread }
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
