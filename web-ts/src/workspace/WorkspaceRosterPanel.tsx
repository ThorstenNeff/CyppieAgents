// CYP-650 (P7, Epic CYP-640) — the OPERATOR-only workspace roster + recent operator-audit. Parity with CMP
// WorkspaceRosterPanel (CYP-186). This component is mounted ONLY for an operator (App gates the whole window — a
// member never receives it, the enumeration seam); it does not self-gate on a flag. Rows show a short, non-
// identifying label + the tier as TEXT (never colour alone). The audit list is content-free (verb + path + time).
import type { WorkspaceMember, OperatorAudit } from '../types/generated/contract'
import { memberLabel, tierLabel, auditLine, formatAuditTime } from './rosterModel'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'

// CYP-679: members + audit are two INDEPENDENT fetches feeding two sections — each gets its own honest load-error +
// retry (mirrors the CommPanel channels+timeline split), length-gated so live/retry data hides it; a failed load ≠
// the genuinely-empty "Keine Mitglieder."/"Keine Aktionen." state.
export function WorkspaceRosterPanel({
  members,
  audit,
  membersLoadError = false,
  onRetryMembers,
  auditLoadError = false,
  onRetryAudit,
}: {
  members: readonly WorkspaceMember[]
  audit: readonly OperatorAudit[]
  membersLoadError?: boolean
  onRetryMembers?: () => void
  auditLoadError?: boolean
  onRetryAudit?: () => void
}) {
  return (
    <div className="workspace-roster" data-testid="workspace-roster">
      <section className="roster-section" aria-label="Mitglieder">
        <h3 className="roster-title">Mitglieder</h3>
        {members.length === 0 ? (
          membersLoadError ? (
            <LoadErrorRetry testId="workspace-roster.members.loadError" onRetry={onRetryMembers ?? (() => undefined)} />
          ) : (
            <p className="roster-empty" data-testid="workspace-roster.members-empty">
              Keine Mitglieder.
            </p>
          )
        ) : (
          <ul className="roster-list" data-testid="workspace-roster.members">
            {members.map((m) => (
              <li className="roster-row" key={m.identityId} data-testid={`workspace-roster.member.${m.identityId}`}>
                <span className="roster-label">{memberLabel(m)}</span>
                <span className="roster-tier" data-testid={`workspace-roster.tier.${m.identityId}`}>
                  {tierLabel(m.tier)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="roster-section" aria-label="Letzte Operator-Aktionen">
        <h3 className="roster-title">Letzte Operator-Aktionen</h3>
        {audit.length === 0 ? (
          auditLoadError ? (
            <LoadErrorRetry testId="workspace-roster.audit.loadError" onRetry={onRetryAudit ?? (() => undefined)} />
          ) : (
            <p className="roster-empty" data-testid="workspace-roster.audit-empty">
              Keine Aktionen.
            </p>
          )
        ) : (
          <ul className="roster-list" data-testid="workspace-roster.audit">
            {audit.map((a, i) => (
              <li className="roster-row" key={`${a.tsMs}-${i}`} data-testid="workspace-roster.audit-row">
                <span className="roster-audit-time">{formatAuditTime(a.tsMs)}</span>
                <span className="roster-audit-actor">{a.actor}</span>
                <span className="roster-audit-line">{auditLine(a)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
