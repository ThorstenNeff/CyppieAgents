// CYP-888 (NR-1) — the vertical nav-rail SHELL: a persistent rail on the left + a content pane on the right, gated on
// the responsive rule (landscape + short-edge ≥ ~600dp; see navRailGate.ts). "Fullscreen" fills the right pane while the
// rail stays visible. Below the gate (portrait / small short-edge) the shell is a pure passthrough — it renders its
// children unchanged, so the canvas is byte-for-byte what it was before the rail existed.
//
// Placement (uiux2 shell-slot, cross-team canonical): this wraps ONLY the desktop canvas region (`.workspace-desktop`).
// The always-visible chrome above it (workspace-bar, hub switcher, security-tier badge, banners) stays full-width and
// untouched — those must never be squeezed beside the rail (the tier/banner always-visible honesty).
//
// Scope of NR-1: structure + responsive gate + content pane only. The rail DESTINATIONS (Canvas/PO/Workers/Settings,
// one-active) arrive in NR-2 (CYP-889); the shared agent view-model in NR-3 (CYP-890). Per PL, the VISUAL language
// (rail width, the 4 icons, ordering, active/inactive, empty-states) converges cross-team on Team-1's canonical UIUX
// spec — kept deliberately MINIMAL/placeholder here so uiux2 can overlay it without rework or client-drift.
import type { ReactNode } from 'react'
import { useNavRailVisible } from './useNavRail'

export function NavRailShell({ children }: { children: ReactNode }) {
  const showRail = useNavRailVisible()

  // Portrait / short-edge < ~600dp → NO rail. Pure passthrough: the existing canvas tree is unchanged (no wrapper
  // element, so layout/DOM are identical to the pre-rail app). This is the load-bearing "canvas unchanged" guarantee.
  if (!showRail) return <>{children}</>

  return (
    <div className="nav-rail-layout" data-testid="nav-rail-layout">
      {/* The persistent rail. NR-1 is a shell — destinations land in NR-2. Visual is minimal/placeholder pending the
          canonical UIUX spec (PL). aria-label names the landmark; the destination controls provide the labels later. */}
      <nav className="nav-rail" data-testid="nav-rail" aria-label="Navigation" />
      {/* The content pane fills the space right of the rail; a fullscreen destination fills THIS pane, rail visible. */}
      <div className="nav-content-pane" data-testid="nav-content-pane">
        {children}
      </div>
    </div>
  )
}
