// CYP-404 (W6) — the autoscroll-pin predicate, ported from :app:shared's transcriptAtBottom (CYP-393). In the DOM
// this is pure scroll geometry (no LazyColumn layout info): are we within `tolerancePx` of the bottom? Streaming
// deltas and the scroll landing sit a few px off exact, so an EXACT `distance === 0` test would flicker the pin —
// the tolerance is the flicker guard (CYP-393 §2). Short content (scrollHeight <= clientHeight) is "at bottom".
export const TRANSCRIPT_BOTTOM_TOLERANCE_PX = 48

export function isNearBottom(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  tolerancePx: number = TRANSCRIPT_BOTTOM_TOLERANCE_PX,
): boolean {
  const distanceFromBottom = scrollHeight - scrollTop - clientHeight
  return distanceFromBottom <= tolerancePx
}
