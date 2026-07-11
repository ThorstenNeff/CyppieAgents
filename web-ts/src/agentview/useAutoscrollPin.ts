// CYP-404 (W6) — auto-follow with pin-retention over a native-scrolling container (CYP-393 parity). Follow keys
// on the TAIL SIGNATURE (not row count alone: a streaming assistant row grows in place, same count), so new
// content scrolls to the bottom only while PINNED. A user scroll away from the bottom releases the pin; scrolling
// back within tolerance resumes it. All the "is at bottom" math is the pure isNearBottom (tested there).
import { useCallback, useLayoutEffect, useRef } from 'react'
import { isNearBottom } from './autoscrollPin'

export function useAutoscrollPin(tailSignature: string): {
  ref: React.RefObject<HTMLDivElement | null>
  onScroll: () => void
} {
  const ref = useRef<HTMLDivElement | null>(null)
  const pinned = useRef(true)

  // On new tail content, if still pinned, stick to the bottom (after layout so scrollHeight is current).
  useLayoutEffect(() => {
    const el = ref.current
    if (el !== null && pinned.current) el.scrollTop = el.scrollHeight
  }, [tailSignature])

  // A user scroll updates the pin: released when scrolled up past the tolerance, resumed at the bottom.
  const onScroll = useCallback(() => {
    const el = ref.current
    if (el === null) return
    pinned.current = isNearBottom(el.scrollTop, el.scrollHeight, el.clientHeight)
  }, [])

  return { ref, onScroll }
}
