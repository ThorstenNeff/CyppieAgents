// CYP-650 (P7, Epic CYP-640) — the pure workspace-roster / operator-audit policy. Parity with CMP
// WorkspaceRosterPanel (CYP-186). Framework-free + unit-tested. The privacy rule (short, NON-identifying labels;
// no email / full identity) lives here so it is proven, not asserted in a component.
import type { WorkspaceMember, OperatorAudit } from '../types/generated/contract'
import { AUTH_ROLE } from '../auth/authRole'

/** The short, non-identifying member label: the friendly displayName when the server supplies one, else the first 8
 *  chars of the identityId (CMP `shortId`). Never the full identity / email. */
export function memberLabel(m: WorkspaceMember): string {
  const name = m.displayName?.trim()
  return name != null && name !== '' ? name : shortId(m.identityId)
}

/** First 8 chars of an identity id — enough to distinguish rows, not enough to identify a person. */
export function shortId(identityId: string): string {
  return identityId.slice(0, 8)
}

/** The tier as human TEXT (never colour alone, WCAG 1.4.1). OPERATOR (any case) → "Operator", else "Mitglied". */
export function tierLabel(tier: string): string {
  return tier.toUpperCase() === AUTH_ROLE.OPERATOR ? 'Operator' : 'Mitglied'
}

/** A content-free one-line audit summary: the verb + path only (no bodies, no query secrets — the server sends the
 *  method + path already stripped of any payload). */
export function auditLine(a: OperatorAudit): string {
  return `${a.method} ${a.path}`
}

/** Local HH:MM:SS for an audit timestamp (24h, locale-stable). */
export function formatAuditTime(tsMs: number): string {
  return new Date(tsMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}
