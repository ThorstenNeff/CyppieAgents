// CYP-402 (W4) — a single DOM window: positioned via CSS transform, dragged by its title bar and resized by the
// bottom-end grip, both through Pointer Events into the store (which applies the CYP-26 clamp + resize floors).
// Focus (pointerdown → bringToFront) is native DOM stacking — no z-order overlay problem (Spec 14 §5). The
// geometry math is all in windowReducer.ts; this only translates pointer deltas into store actions.
import { useRef } from 'react'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

export function WindowFrame({
  window: w,
  children,
  titleAccessory,
}: {
  window: WindowState
  children?: React.ReactNode
  /** CYP-641: an optional passive marker rendered at the END of the title bar (the per-window activity badge).
   *  Non-interactive; it sits inside the draggable header, which is fine — a badge press just begins a drag. */
  titleAccessory?: React.ReactNode
}) {
  const focus = useWindowStore((s) => s.focus)
  const moveBy = useWindowStore((s) => s.moveBy)
  const resizeBy = useWindowStore((s) => s.resizeBy)
  const last = useRef<{ x: number; y: number } | null>(null)

  const capture = (e: React.PointerEvent) => {
    const el = e.currentTarget as Element & { setPointerCapture?: (id: number) => void }
    el.setPointerCapture?.(e.pointerId) // guarded: jsdom may not implement it
  }

  const beginDrag = (e: React.PointerEvent) => {
    focus(w.id)
    last.current = { x: e.clientX, y: e.clientY }
    capture(e)
  }
  const onDrag = (e: React.PointerEvent) => {
    if (last.current === null) return
    moveBy(w.id, e.clientX - last.current.x, e.clientY - last.current.y)
    last.current = { x: e.clientX, y: e.clientY }
  }
  const beginResize = (e: React.PointerEvent) => {
    e.stopPropagation()
    focus(w.id)
    last.current = { x: e.clientX, y: e.clientY }
    capture(e)
  }
  const onResize = (e: React.PointerEvent) => {
    if (last.current === null) return
    resizeBy(w.id, e.clientX - last.current.x, e.clientY - last.current.y)
    last.current = { x: e.clientX, y: e.clientY }
  }
  const end = () => {
    last.current = null
  }

  return (
    <section
      className="window"
      data-window-id={w.id}
      style={{ transform: `translate(${w.x}px, ${w.y}px)`, width: w.width, height: w.height }}
      onPointerDown={() => focus(w.id)}
    >
      <header
        className="window-title"
        onPointerDown={beginDrag}
        onPointerMove={onDrag}
        onPointerUp={end}
        onPointerCancel={end}
      >
        <span className="window-title-text">{w.title}</span>
        {titleAccessory}
      </header>
      <div className="window-body">{children}</div>
      <div
        className="window-resize"
        role="presentation"
        aria-hidden="true"
        onPointerDown={beginResize}
        onPointerMove={onResize}
        onPointerUp={end}
        onPointerCancel={end}
      />
    </section>
  )
}
