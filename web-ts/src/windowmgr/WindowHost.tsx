// CYP-402 (W4) — the desktop surface: renders the store's windows in list order (index → z-index, native DOM
// stacking) and reports its own measured size to the store (so the CYP-26 clamp + host-shrink snap-back have a
// viewport). ResizeObserver when available, window resize as the fallback.
import { useEffect, useRef } from 'react'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

export function WindowHost({ children }: { children: (win: WindowState) => React.ReactNode }) {
  const windows = useWindowStore((s) => s.windows)
  const setHost = useWindowStore((s) => s.setHost)
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

  return (
    <div ref={ref} className="window-host" style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden' }}>
      {windows.map((win, i) => (
        <div key={win.id} className="window-slot" style={{ position: 'absolute', top: 0, left: 0, zIndex: i }}>
          {children(win)}
        </div>
      ))}
    </div>
  )
}
