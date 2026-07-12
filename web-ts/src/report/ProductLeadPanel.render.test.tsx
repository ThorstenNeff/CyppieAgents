// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act } from '@testing-library/react'
import { ProductLeadPanel } from './ProductLeadPanel'
import type { ReportSnapshot } from '../types/generated/contract'

const snap = (over: Partial<ReportSnapshot> = {}): ReportSnapshot => ({
  id: 'r1',
  type: 'status',
  generatedAt: 1_700_000_000_000,
  projectId: 'p',
  sources: ['events'],
  window: {},
  sections: [],
  ...over,
})

afterEach(cleanup)

describe('ProductLeadPanel (CYP-464)', () => {
  it('operator-gate fail-closed: a non-operator gets ONLY the gate-hint (no trigger/list) and never fetches (tooth 4)', () => {
    const fetchReports = vi.fn().mockResolvedValue([])
    const { getByTestId, queryByTestId } = render(
      <ProductLeadPanel operator={false} fetchReports={fetchReports} generateReport={vi.fn()} />,
    )
    expect(getByTestId('productLead.gateHint')).toBeTruthy()
    expect(queryByTestId('productLead.trigger')).toBeNull()
    expect(queryByTestId('productLead.list')).toBeNull()
    expect(fetchReports).not.toHaveBeenCalled() // no report query for a non-operator
  })

  it('lists snapshots newest-first with a prominent "As of" (snapshot ≠ live, tooth 1)', async () => {
    const fetchReports = vi.fn().mockResolvedValue([snap({ id: 'r1' })])
    const { findByTestId } = render(<ProductLeadPanel operator fetchReports={fetchReports} generateReport={vi.fn()} />)
    expect(await findByTestId('productLead.snapshot.r1')).toBeTruthy()
    expect((await findByTestId('productLead.snapshot.r1.ts')).textContent).toMatch(/^Stand: /)
  })

  it('detail shows As-of heading + provenance; DEFECTS shows the advisory note (teeth 1/2/3)', async () => {
    const defects = snap({ id: 'd1', type: 'defects', sources: ['error.*', 'log.dropped'] })
    const fetchReports = vi.fn().mockResolvedValue([defects])
    const { findByTestId, getByTestId } = render(<ProductLeadPanel operator fetchReports={fetchReports} generateReport={vi.fn()} />)
    fireEvent.click(await findByTestId('productLead.snapshot.d1'))
    expect(getByTestId('productLead.detail.asOf').textContent).toMatch(/^Stand: /)
    expect(getByTestId('productLead.detail.provenance').textContent).toContain('log.dropped') // named sources
    expect(getByTestId('productLead.detail.advisory')).toBeTruthy() // observed, not exhaustive
  })

  it('a STATUS report shows NO defects-advisory note (advisory is defects-only)', async () => {
    const fetchReports = vi.fn().mockResolvedValue([snap({ id: 's1', type: 'status' })])
    const { findByTestId, getByTestId, queryByTestId } = render(<ProductLeadPanel operator fetchReports={fetchReports} generateReport={vi.fn()} />)
    fireEvent.click(await findByTestId('productLead.snapshot.s1'))
    expect(getByTestId('productLead.detail')).toBeTruthy()
    expect(queryByTestId('productLead.detail.advisory')).toBeNull()
  })

  it('a defect row carries severity as glyph + aria-label, never colour alone (tooth 5)', async () => {
    const withDefect = snap({
      id: 'd2',
      type: 'defects',
      sections: [{ key: 'errors', title: 'Fehler', items: [{ text: 'boom', severity: 'error' }] }],
    })
    const { findByTestId, getByTestId } = render(<ProductLeadPanel operator fetchReports={vi.fn().mockResolvedValue([withDefect])} generateReport={vi.fn()} />)
    fireEvent.click(await findByTestId('productLead.snapshot.d2'))
    const row = getByTestId('productLead.detail.defect.0.error')
    expect(row.textContent).toContain('boom')
    expect(row.querySelector('.product-lead-sev')?.getAttribute('aria-label')).toBe('Fehler') // label carried, not colour alone
  })

  it('triggers a new report; buttons are disabled while generating (on-demand, no double-trigger, tooth 7)', async () => {
    let resolve!: (s: ReportSnapshot) => void
    const generateReport = vi.fn().mockReturnValue(new Promise<ReportSnapshot>((r) => (resolve = r)))
    const { getByTestId, findByTestId } = render(
      <ProductLeadPanel operator fetchReports={vi.fn().mockResolvedValue([])} generateReport={generateReport} />,
    )
    await findByTestId('productLead.trigger')
    fireEvent.click(getByTestId('productLead.trigger.status'))
    expect(generateReport).toHaveBeenCalledWith('status')
    expect((getByTestId('productLead.trigger.status') as HTMLButtonElement).disabled).toBe(true) // no double-trigger
    expect(getByTestId('productLead.generating')).toBeTruthy()
    await act(async () => {
      resolve(snap({ id: 'new1', type: 'status' }))
    })
    expect(await findByTestId('productLead.snapshot.new1')).toBeTruthy() // prepended
    expect((getByTestId('productLead.trigger.status') as HTMLButtonElement).disabled).toBe(false)
  })

  it('empty is gated on !loading — no "Keine Reports" flash during the fetch (tooth 6)', async () => {
    let resolve!: (s: ReportSnapshot[]) => void
    const fetchReports = vi.fn().mockReturnValue(new Promise<ReportSnapshot[]>((r) => (resolve = r)))
    const { queryByTestId, findByTestId } = render(<ProductLeadPanel operator fetchReports={fetchReports} generateReport={vi.fn()} />)
    expect(queryByTestId('productLead.empty')).toBeNull() // still loading → no flash
    await act(async () => {
      resolve([])
    })
    expect(await findByTestId('productLead.empty')).toBeTruthy() // loaded AND empty → honest empty
  })
})
