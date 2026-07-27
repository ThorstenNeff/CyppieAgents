// CYP-833 (CYP-807-A5) — the SINGLE-SOURCED, FAIL-CLOSED WebSocket-close terminal verdict for the new A5 remote
// connector. Fixes the CYP-289/fail-OPEN hazard: `reconnectingSocket.ts:73` treats ONLY code 1008 as terminal and
// reconnects on everything else, so an unrecognized (possibly security-relevant) drop silently reconnects. Here the
// polarity is INVERTED to fail-closed: a close is TERMINAL unless its code is on an explicit known-reconnectable
// allowlist.
//
// ★ SCOPE (coordinator-ratified option (a), 2026-07-27): this is used ONLY by the new A5 RemoteHubConnector — zero
// local-hub regression risk. The 3 legacy fail-open sites (reconnectingSocket.ts:73 · App.tsx:115 commCloseToConnection
// · eventLogStore) are DELIBERATELY LEFT UNTOUCHED; their allowlist-preserving migration onto this fn is the separate
// follow-up CYP-839 (that is where the local-hub reconnect-regression risk lives, so it gets its own review).

/**
 * WS close codes that are KNOWN-TRANSIENT → reconnectable (a non-terminal drop; the connector may re-dial). Set from
 * Tester2's measured reconnectable-close-code baseline (qa-state/reconnectable-closecode-baseline.md) so a legit remote
 * transient is not falsely killed:
 *   1001 going away · 1005 no-status-received · 1006 abnormal closure (no close frame / network blip) ·
 *   1011 server error (transient hiccup, retry) · 1012 service restart · 1013 try again later.
 * Everything NOT on this list is terminal (fail-closed): 1008 (policy violation / auth revoke), 1000 (a clean normal
 * close = done, deliberate — see below), and every UNRECOGNIZED numeric code.
 */
export const RECONNECTABLE_CLOSE_CODES: ReadonlySet<number> = new Set([1001, 1005, 1006, 1011, 1012, 1013])

/**
 * The fail-closed terminal-close verdict. TRUE (terminal → no reconnect) unless the code is explicitly known-reconnectable.
 * CYP-833 invariant: an UNRECOGNIZED numeric close code must NEVER silently reconnect — it fails closed (matching the
 * CYP-824 server=fail-closed doctrine).
 * ★ `undefined` (transport supplied NO code) → RECONNECTABLE (not terminal): a no-code close is the abnormal-without-frame
 * transient (≈ 1005/1006), not a security signal — security-relevant closes carry an explicit code (1008). This follows
 * Tester2's baseline; it REVERSES the earlier undefined→terminal reading (flagged to coordinator). 1000 stays terminal.
 */
export function isTerminalClose(code: number | undefined): boolean {
  if (code === undefined) return false // no-code = transient blip → reconnectable (Tester2 baseline; flagged reversal)
  return !RECONNECTABLE_CLOSE_CODES.has(code)
}
