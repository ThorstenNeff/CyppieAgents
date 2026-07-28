// CYP-889 (NR-2) — the pure nav-rail destination model. Built from the live roster; deterministic so the ordering +
// present-iff honesty are the tooth targets. Canonical spec CYP-893 §3 + PL ruling 2026-07-28 (shared model, both
// clients IDENTICAL — ordering is part of the model, not per-surface):
//
//   Canvas (fixed, top) → PO → PRODUCT_LEAD → Worker (scrollable) → Settings (fixed, bottom)
//
// The fixed STRUCTURAL destinations bracket the identity-based agent destinations. The lead agents (PO then
// PRODUCT_LEAD) sit non-scrolling at the top of the agent block; workers scroll below. Each of the three roles is its
// OWN distinct destination — PRODUCT_LEAD is never bundled into Worker, never collapsed into PO, never dropped.
import type { Agent } from '../types/generated/contract'

export const CANVAS_DESTINATION_ID = 'canvas'
export const SETTINGS_DESTINATION_ID = 'settings'

export type NavDestinationKind = 'canvas' | 'agent' | 'settings'
export type NavAgentRole = 'PO' | 'PRODUCT_LEAD' | 'WORKER'

export interface NavDestination {
  /** Stable id: the fixed ids for canvas/settings, the agentId for an agent destination. */
  id: string
  kind: NavDestinationKind
  label: string
  /** Present iff kind === 'agent'. */
  agentId?: string
  role?: NavAgentRole
}

/**
 * Build the ordered rail destinations from the roster. Order (PL-ruled shared model): Canvas, then PO(s), then
 * PRODUCT_LEAD(s), then Worker(s) (roster order within each role), then Settings. Honesty rules (CYP-893 §3 + PL):
 * every role is present-iff an agent of that role exists (no dead/greyed row); if more than one agent of a role exists
 * ALL are shown, never collapsed (don't hide reality). The three roles are DISTINCT destinations.
 */
export function navDestinations(roster: readonly Agent[]): NavDestination[] {
  const agentDest = (a: Agent, role: NavAgentRole): NavDestination => ({
    id: a.id,
    kind: 'agent',
    label: a.name,
    agentId: a.id,
    role,
  })
  const byRole = (role: NavAgentRole): NavDestination[] => roster.filter((a) => a.role === role).map((a) => agentDest(a, role))
  return [
    { id: CANVAS_DESTINATION_ID, kind: 'canvas', label: 'Canvas' },
    ...byRole('PO'),
    ...byRole('PRODUCT_LEAD'),
    ...byRole('WORKER'),
    { id: SETTINGS_DESTINATION_ID, kind: 'settings', label: 'Einstellungen' },
  ]
}

/** The active destination for a given selected id, falling back to Canvas when the id no longer resolves (e.g. an
 *  agent left the roster) — so the pane never renders a dead destination. Canvas is always present (index 0). */
export function activeDestination(destinations: readonly NavDestination[], activeId: string): NavDestination {
  return destinations.find((d) => d.id === activeId) ?? destinations[0]
}
