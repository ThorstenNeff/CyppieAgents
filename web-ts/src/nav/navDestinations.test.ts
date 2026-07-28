// CYP-889 (NR-2) — the destination-model teeth: the PL-ruled shared ordering (Canvas → PO → PRODUCT_LEAD → Worker →
// Settings), present-iff for every role (no dead rows), PRODUCT_LEAD as its own DISTINCT destination (never bundled into
// Worker / collapsed into PO / dropped), and the active-destination fallback to Canvas.
import { describe, it, expect } from 'vitest'
import { navDestinations, activeDestination, CANVAS_DESTINATION_ID, SETTINGS_DESTINATION_ID } from './navDestinations'
import type { Agent } from '../types/generated/contract'

const agent = (id: string, role: Agent['role']): Agent => ({ id, name: id.toUpperCase(), role, worktree: id })

describe('CYP-889 — navDestinations (model + ordering)', () => {
  it('★ ordering is Canvas → PO → PRODUCT_LEAD → Worker → Settings (PL-ruled shared model)', () => {
    // MUT: put PRODUCT_LEAD after Worker, or before PO → this reds. MUT: fold PL into the worker filter → order/role red.
    const roster = [agent('w1', 'WORKER'), agent('pl', 'PRODUCT_LEAD'), agent('po', 'PO'), agent('w2', 'WORKER')]
    const ids = navDestinations(roster).map((d) => d.id)
    expect(ids).toEqual([CANVAS_DESTINATION_ID, 'po', 'pl', 'w1', 'w2', SETTINGS_DESTINATION_ID])
  })

  it('★ PRODUCT_LEAD is a DISTINCT agent destination — its own role, never bundled into Worker', () => {
    const dests = navDestinations([agent('pl', 'PRODUCT_LEAD')])
    const pl = dests.find((d) => d.id === 'pl')
    expect(pl).toBeTruthy()
    expect(pl?.kind).toBe('agent')
    expect(pl?.role).toBe('PRODUCT_LEAD') // NOT 'WORKER'
  })

  it('★ present-iff: no PO / no PL / no worker → no dead row for that role; fixed dests always present', () => {
    // MUT: emit a PO destination when no PO exists → this reds (a dead greyed row is forbidden).
    const dests = navDestinations([agent('w1', 'WORKER')])
    expect(dests.map((d) => d.id)).toEqual([CANVAS_DESTINATION_ID, 'w1', SETTINGS_DESTINATION_ID])
    expect(dests.some((d) => d.role === 'PO')).toBe(false)
    expect(dests.some((d) => d.role === 'PRODUCT_LEAD')).toBe(false)
  })

  it('★ multiple agents of a role are ALL shown (never collapsed)', () => {
    const dests = navDestinations([agent('po1', 'PO'), agent('po2', 'PO')])
    expect(dests.filter((d) => d.role === 'PO').map((d) => d.id)).toEqual(['po1', 'po2'])
  })

  it('★ empty roster → only the fixed Canvas + Settings destinations', () => {
    expect(navDestinations([]).map((d) => d.id)).toEqual([CANVAS_DESTINATION_ID, SETTINGS_DESTINATION_ID])
  })

  it('★ activeDestination resolves the id, and falls back to Canvas when the id is gone (no dead active target)', () => {
    // MUT: return destinations.find(...) without the ?? fallback → a removed agent yields undefined (dead pane) → reds.
    const dests = navDestinations([agent('po', 'PO')])
    expect(activeDestination(dests, 'po').id).toBe('po')
    expect(activeDestination(dests, 'ghost-worker').id).toBe(CANVAS_DESTINATION_ID)
  })
})
