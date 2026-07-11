// CYP-405 (W7) — the CLIENT-side terminal gate. The server is authoritative (CYP-394 TerminalAccess: the
// production grant store is empty → /ws/terminal is operator-only). This is the fail-closed mirror: the web
// only OFFERS the shell on the operator serve (operator-token global present). Defence in depth + honest UI —
// a non-operator never sees an "open shell" affordance that the server would reject anyway.
import { isOperatorServe } from '../platform/operatorToken'

export function mayOpenTerminalClient(): boolean {
  return isOperatorServe()
}
