// CYP-906 (Edit E3) — the operator-only edit affordance (MVP scope, PL-ruled 2026-07-28). The client shows the edit
// trigger ONLY for an OPERATOR editing an OPERATOR-authored message: `operator ∧ from === OPERATOR_AGENT_ID` — fully
// client-derivable (no self-identity / no server signal; AuthMe is content-free, §5b). The SERVER stays the authority:
// a PUT edit is 403'd for a non-author (render-mirrors-authority, like the CYP-890 read-only composer). Broader
// "anyone edits their own message" is the §5b self-identity follow-up behind the same editedAt contract.
export function canEditMessage(operator: boolean, from: string, operatorAgentId: string): boolean {
  return operator && from === operatorAgentId
}
