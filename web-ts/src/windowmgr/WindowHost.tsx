// CYP-402 (W4) / CYP-664 (P12) — the window surface. It measures its own size into the store (so the CYP-26 clamp +
// host-shrink snap-back have a viewport) and renders the store's windows.
//
// It is ALSO the responsive surface (CYP-664): at a compact size-class it presents the windows as a phone-pager
// (one page per window, only the focused page visible, an honest dot/counter indicator) instead of the floating
// desktop tiling. Crucially it renders each window's content EXACTLY ONCE, keyed by window id, in BOTH modes — the
// size-class only toggles layout (a slot's CSS + a `hidden` attribute), never unmounting. So a size-class switch
// keeps every window mounted: open windows, focus, AND in-progress inputs survive (no remount, no double-mount).
import { useEffect, useRef } from 'react'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'
import { isCompact, pagerView, PHONE_PAGER_TESTID as TID } from './pagerModel'
import { PagerIndicator } from './PagerIndicator'

export function WindowHost({ children }: { children: (win: WindowState) => React.ReactNode }) {
  const windows = useWindowStore((s) => s.windows)
  const windowOrder = useWindowStore((s) => s.windowOrder)
  const host = useWindowStore((s) => s.host)
  const setHost = useWindowStore((s) => s.setHost)
  const focus = useWindowStore((s) => s.focus)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = ref.current
    if (el === null) return
    const measure = () => setHost(el.clientWidth, el.clientHeight)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(measure)
      ro.observe(el)
      return () => ro.disconnect()
    }
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [setHost])

  const compact = isCompact(host.width, host.height)
  // focus = the top of the z-order (last in `windows`); the pager's current page follows it.
  const focusedId = windows.length > 0 ? windows[windows.length - 1].id : null
  // the pager pages follow the STABLE registration order (windowOrder) — never re-sorted by focus. Map to live states.
  const orderedWindows = windowOrder
    .map((id) => windows.find((w) => w.id === id))
    .filter((w): w is WindowState => w !== undefined)
  const view = pagerView(
    orderedWindows.map((w) => ({ id: w.id, title: w.title })),
    focusedId,
  )

  return (
    <div
      ref={ref}
      className={`window-host${compact ? ' window-host-compact' : ''}`}
      data-testid={compact ? TID.pager : undefined}
      style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden' }}
    >
      {compact && windows.length === 0 && (
        <p className="phone-pager-empty" role="note" data-testid={TID.empty}>
          Keine Fenster geöffnet.
        </p>
      )}
      {windows.map((win, i) => {
        // compact: only the focused page is shown (`hidden` = display:none, but the element STAYS mounted → inputs
        // preserved). desktop: absolute floating, list index = z-index.
        const pageHidden = compact && win.id !== focusedId
        return (
          <div
            key={win.id}
            className="window-slot"
            data-testid={compact ? TID.page(win.id) : undefined}
            hidden={pageHidden}
            style={compact ? undefined : { position: 'absolute', top: 0, left: 0, zIndex: i }}
          >
            {children(win)}
          </div>
        )
      })}
      {compact && <PagerIndicator view={view} onSelect={focus} />}
    </div>
  )
}
