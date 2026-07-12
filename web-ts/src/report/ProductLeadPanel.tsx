// CYP-464 (P2-d) — Product-Lead report panel: on-demand snapshot generator + newest-first list + read-only detail.
// Ported from Compose ProductLeadPanel. The whole surface exists to keep ONE honesty (spec §0): a snapshot is a
// point-in-time OBSERVATION, never live. So every snapshot carries a prominent "As of: <ts>" (list + detail heading)
// + a may-be-outdated hint + provenance (named sources + window); DEFECTS are advisory (observed, not exhaustive).
// Operator-gated fail-closed: no operator token ⇒ only the gate-hint (NO trigger, NO list, NO fetch) — reports
// aggregate operator-gated observability, so a non-operator gets no report data at all (content-free items either way).
// Severity reuses the one house source (glyph + label, colour never alone). Empty is gated on !loading (no flash).
import { useEffect, useState } from 'react'
import type { ReportSnapshot } from '../types/generated/contract'
import {
  REPORT_TYPES,
  reportTypeLabel,
  asOfLabel,
  provenanceText,
  severityGlyph,
  severityLabel,
  REPORT_TEXT as T,
  type ReportType,
  type Severity,
} from './productLeadModel'

export interface ProductLeadPanelProps {
  operator: boolean
  fetchReports: () => Promise<ReportSnapshot[]>
  generateReport: (type: ReportType) => Promise<ReportSnapshot>
}

export function ProductLeadPanel({ operator, fetchReports, generateReport }: ProductLeadPanelProps) {
  const [reports, setReports] = useState<readonly ReportSnapshot[]>([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState<ReportType | null>(null)
  const [error, setError] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // fail-closed: only an operator fetches/sees report data (§3). A non-operator never triggers the query.
  useEffect(() => {
    if (!operator) return
    let live = true
    setLoading(true)
    fetchReports()
      .then((r) => {
        if (!live) return
        setReports(r)
        setLoading(false)
      })
      .catch(() => live && setLoading(false))
    return () => {
      live = false
    }
  }, [operator, fetchReports])

  if (!operator) {
    // GATED neutral (not green, not error): a gate, not a failure — no trigger, no list (§3, tooth 4).
    return (
      <div className="product-lead" data-testid="productLead.panel">
        <p className="product-lead-gate" role="note" data-testid="productLead.gateHint">
          {T.gateHint}
        </p>
      </div>
    )
  }

  const runReport = (type: ReportType) => {
    setError(false)
    setGenerating(type)
    generateReport(type)
      .then((snap) => {
        setReports((prev) => [snap, ...prev.filter((s) => s.id !== snap.id)]) // newest first, immutable per run
        setSelectedId(snap.id)
        setGenerating(null)
      })
      .catch(() => {
        setError(true)
        setGenerating(null)
      })
  }

  const selected = reports.find((s) => s.id === selectedId) ?? null
  const busy = generating !== null

  return (
    <div className="product-lead" data-testid="productLead.panel">
      {/* trigger bar stays put (not a pane); disabled while generating → no double-trigger (tooth 7). */}
      <div className="product-lead-trigger" data-testid="productLead.trigger">
        <span className="product-lead-generate-label">{T.generate}</span>
        {REPORT_TYPES.map((type) => (
          <button
            key={type}
            type="button"
            data-testid={`productLead.trigger.${type}`}
            disabled={busy}
            onClick={() => runReport(type)}
          >
            {reportTypeLabel(type)}
          </button>
        ))}
        {generating !== null && (
          <span className="product-lead-generating" role="status" data-testid="productLead.generating">
            {T.generating}
          </span>
        )}
        {error && (
          <span className="product-lead-error" role="alert" data-testid="productLead.error">
            {T.error}
          </span>
        )}
      </div>

      <div className="product-lead-body">
        <ol className="product-lead-list transcript-scroll" data-testid="productLead.list">
          {/* empty gated on !loading — never flash "no reports" during the fetch window (tooth 6). */}
          {reports.length === 0 && !loading ? (
            <p className="product-lead-empty" role="status" data-testid="productLead.empty">
              {T.empty}
            </p>
          ) : (
            reports.map((s) => (
              <li
                key={s.id}
                className={`product-lead-snapshot${s.id === selectedId ? ' selected' : ''}`}
                data-testid={`productLead.snapshot.${s.id}`}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedId(s.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    setSelectedId(s.id)
                  }
                }}
              >
                <span className="product-lead-snapshot-type">{reportTypeLabel(s.type as ReportType)}</span>
                <span className="product-lead-snapshot-ts" data-testid={`productLead.snapshot.${s.id}.ts`}>
                  {asOfLabel(s.generatedAt)}
                </span>
              </li>
            ))
          )}
        </ol>

        {selected !== null && <DetailPane snapshot={selected} onBack={() => setSelectedId(null)} />}
      </div>
    </div>
  )
}

function DetailPane({ snapshot, onBack }: { snapshot: ReportSnapshot; onBack: () => void }) {
  return (
    <div className="product-lead-detail" role="region" aria-label={reportTypeLabel(snapshot.type as ReportType)} data-testid="productLead.detail">
      <button type="button" className="product-lead-back" data-testid="productLead.back" onClick={onBack} aria-label="Zurück">
        {T.back}
      </button>
      {/* prominent As-of + may-be-outdated: a snapshot is never rendered as the current standing state (§0/§6). */}
      <h3 data-testid="productLead.detail.asOf">{asOfLabel(snapshot.generatedAt)}</h3>
      <p className="product-lead-snapshot-hint">{T.snapshotHint}</p>
      {/* provenance: named sources + observation window — observed, not authoritative (never invented completeness). */}
      <p className="product-lead-provenance" data-testid="productLead.detail.provenance">
        {provenanceText(snapshot)}
      </p>
      {/* DEFECTS register is advisory — observed, not exhaustive/confirmed (tooth 2). */}
      {snapshot.type === 'defects' && (
        <p className="product-lead-advisory" role="note" data-testid="productLead.detail.advisory">
          {T.advisory}
        </p>
      )}
      {snapshot.sections.map((section) => (
        <section key={section.key} className="product-lead-section" data-testid={`productLead.detail.section.${section.key}`}>
          <h4>{section.title}</h4>
          <ul>
            {section.items.map((item, i) => (
              <li
                key={i}
                className="product-lead-item"
                data-testid={`productLead.detail.defect.${i}${item.severity ? `.${item.severity}` : ''}`}
              >
                {item.severity && (
                  // severity = colour + glyph + label; the glyph carries the label as aria-label (colour never alone).
                  <span
                    className={`product-lead-sev event-sev-${item.severity}`}
                    aria-label={severityLabel(item.severity as Severity)}
                    data-severity={item.severity}
                  >
                    {severityGlyph(item.severity as Severity)}
                  </span>
                )}
                <span className="product-lead-item-text">{item.text}</span>
                {item.refLabel && <span className="product-lead-item-ref">{item.refLabel}</span>}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
