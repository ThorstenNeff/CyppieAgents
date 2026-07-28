// CYP-888/889 (NR-1/NR-2) — the vertical nav-rail SHELL: a persistent rail on the left + a content pane on the right,
// gated on the responsive rule (landscape + short-edge ≥ ~600dp; see navRailGate.ts). Below the gate the shell is a pure
// passthrough that renders the CANVAS destination unchanged (nav concept N/A in portrait; canvas = today's WindowHost).
//
// NR-2 adds the destinations (Canvas · PO · PRODUCT_LEAD · Worker · Settings; navDestinations.ts) with exactly one
// active, a render-prop pane that renders the active destination, and the measured visual (CYP-887 web-adaptation):
//  - Real navigation, NOT a tablist: `<nav>` + `<button>` items, aria-current on the one active (NOT role=tab).
//  - Active carrier is FORM + aria-current + glyph-TONE, never colour/tint alone (the pill fill is <3:1 — see index.css).
//  - The rail is PURE NAV: no status/health/trust on an item (Agent.runState is deliberately not read here).
import type { ReactNode } from 'react'
import { useNavRailVisible } from './useNavRail'
import { activeDestination, type NavDestination } from './navDestinations'

export interface NavRailShellProps {
  destinations: NavDestination[]
  /** The selected destination id (local optimistic view-state, owned by the caller). */
  activeId: string
  /** Select a destination — a pure client view switch (optimistic, NOT operator-gated, no server confirm). */
  onSelect: (id: string) => void
  /** Render the pane for the active destination. Receives the resolved active destination (falls back to Canvas). */
  children: (active: NavDestination) => ReactNode
}

// Fallback glyphs (semantic-parity, no icon dependency — web-ts already uses ●/○/▲; primary identity is the agent
// avatar/color when present). Fill/hollow honesty: ◆ filled = PO hub, ◇ hollow = worker spoke; ◎ (U+25CE bullseye,
// concentric rings) = PRODUCT_LEAD oversight/review — deliberately OUTSIDE the diamond family and NOT ◉ (a Compose
// house-marker) so both surfaces converge. Glyphs are aria-hidden — the label carries the name/role (WCAG 1.4.1).
const GLYPH: Record<string, string> = { canvas: '▦', settings: '⚙', PO: '◆', PRODUCT_LEAD: '◎', WORKER: '◇' }

function glyphFor(d: NavDestination): string {
  if (d.kind === 'agent') return GLYPH[d.role ?? 'WORKER']
  return GLYPH[d.kind]
}

function destTestId(d: NavDestination): string {
  if (d.kind === 'canvas') return 'navRail.dest.canvas'
  if (d.kind === 'settings') return 'navRail.dest.settings'
  const tier = d.role === 'PO' ? 'po' : d.role === 'PRODUCT_LEAD' ? 'lead' : 'worker'
  return `navRail.dest.${tier}.${d.agentId}`
}

function RailItem({ d, active, onSelect }: { d: NavDestination; active: boolean; onSelect: (id: string) => void }) {
  return (
    <button
      type="button"
      className={`nav-rail-item${active ? ' nav-rail-item-active' : ''}`}
      data-testid={destTestId(d)}
      aria-current={active ? 'page' : undefined}
      aria-label={d.label}
      title={d.label}
      onClick={() => {
        if (!active) onSelect(d.id) // self-select is a no-op (already active)
      }}
    >
      <span className="nav-rail-item-glyph" aria-hidden="true">
        {glyphFor(d)}
      </span>
      <span className="nav-rail-item-label">{d.label}</span>
    </button>
  )
}

export function NavRailShell({ destinations, activeId, onSelect, children }: NavRailShellProps) {
  const showRail = useNavRailVisible()
  const canvas = destinations.find((d) => d.kind === 'canvas') ?? destinations[0]
  const active = activeDestination(destinations, activeId)

  // Portrait / short-edge < ~600dp (or unmeasured 0×0) → NO rail. Pure passthrough: render the CANVAS destination
  // unchanged, so the existing canvas is byte-for-byte what it was before the rail existed.
  if (!showRail) return <>{children(canvas)}</>

  // Split the agent destinations: lead tier (PO, PRODUCT_LEAD — non-scrolling, top) vs workers (scrollable). The fixed
  // Canvas/Settings always render (nav chrome must not fail on roster data). "Keine Worker" is a loaded-&-empty signal:
  // shown only when the roster HAS agents but none are workers (never a confident-empty on an unloaded roster).
  const leads = destinations.filter((d) => d.kind === 'agent' && d.role !== 'WORKER')
  const workers = destinations.filter((d) => d.kind === 'agent' && d.role === 'WORKER')
  const settings = destinations.find((d) => d.kind === 'settings')
  const showWorkersEmpty = leads.length + workers.length > 0 && workers.length === 0

  return (
    <div className="nav-rail-layout" data-testid="nav-rail-layout">
      <nav className="nav-rail" data-testid="nav-rail" aria-label="Zwischen Bereichen wechseln">
        <RailItem d={canvas} active={active.id === canvas.id} onSelect={onSelect} />
        {leads.map((d) => (
          <RailItem key={d.id} d={d} active={active.id === d.id} onSelect={onSelect} />
        ))}
        <div className="nav-rail-workers">
          {workers.map((d) => (
            <RailItem key={d.id} d={d} active={active.id === d.id} onSelect={onSelect} />
          ))}
          {showWorkersEmpty && (
            <p className="nav-rail-workers-empty" data-testid="navRail.workersEmpty">
              Keine Worker
            </p>
          )}
        </div>
        {settings && <RailItem d={settings} active={active.id === settings.id} onSelect={onSelect} />}
      </nav>
      <div className="nav-content-pane" data-testid="nav-content-pane">
        {children(active)}
      </div>
    </div>
  )
}
