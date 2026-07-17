// CYP-664 (P12) — the Phone-Pager indicator, web-ts port of the CMP PagerIndicator. Store-agnostic: driven purely by
// a PagerView (derived honestly from the real window list) + an onSelect callback. Up to PAGER_DOT_THRESHOLD pages it
// shows tappable dots; beyond that a compact "N / M" counter (so the row never overflows a narrow phone). The active
// page is carried by shape/size + aria-current, never colour alone (WCAG 1.4.1). Prev/next chevrons flank the row.
import type { PagerView } from './pagerModel'
import { pagerCounterLabel, PHONE_PAGER_TESTID as TID } from './pagerModel'

export function PagerIndicator({ view, onSelect }: { view: PagerView; onSelect: (id: string) => void }) {
  // A single page (or none) needs no pager chrome.
  if (!view.showIndicator) return null
  const { pages, currentIndex, useDots } = view
  const atFirst = currentIndex <= 0
  const atLast = currentIndex >= pages.length - 1

  const go = (index: number) => {
    const page = pages[index]
    if (page) onSelect(page.id)
  }

  return (
    <nav className="phone-pager-indicator" data-testid={TID.indicator} aria-label="Fenster-Seiten">
      <button
        type="button"
        className="phone-pager-nav"
        data-testid={TID.prev}
        aria-label="Vorheriges Fenster"
        disabled={atFirst}
        onClick={() => go(currentIndex - 1)}
      >
        ‹
      </button>

      {useDots ? (
        <div className="phone-pager-dots" role="tablist">
          {pages.map((p, i) => {
            const active = i === currentIndex
            return (
              <button
                key={p.id}
                type="button"
                role="tab"
                className={`phone-pager-dot${active ? ' active' : ''}`}
                data-testid={TID.dot(p.id)}
                aria-label={`${p.title} (Seite ${i + 1} von ${pages.length})`}
                aria-current={active ? 'page' : undefined}
                aria-selected={active}
                onClick={() => go(i)}
              />
            )
          })}
        </div>
      ) : (
        // Over the dot threshold: an honest 1-based page / real total (no phantom).
        <span className="phone-pager-counter" data-testid={TID.counter} aria-live="polite">
          {pagerCounterLabel(view)}
        </span>
      )}

      <button
        type="button"
        className="phone-pager-nav"
        data-testid={TID.next}
        aria-label="Nächstes Fenster"
        disabled={atLast}
        onClick={() => go(currentIndex + 1)}
      >
        ›
      </button>
    </nav>
  )
}
