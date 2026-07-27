// CYP-833 (CYP-807-A5) — the SINGLE-SOURCED, FAIL-CLOSED WebSocket-close terminal verdict for the new A5 remote
// connector. Fixes the CYP-289/fail-OPEN hazard: `reconnectingSocket.ts:73` treats ONLY code 1008 as terminal and
// reconnects on everything else, so an unrecognized (possibly security-relevant) drop silently reconnects. Here the
// polarity is INVERTED to fail-closed: a close is TERMINAL unless its code is on an explicit known-reconnectable
// allowlist.
//
// ★ SCOPE: isTerminalClose is the A5 REMOTE (security-surface) policy. The LOCAL-HUB (loopback, trusted) sites use the
// SEPARATE isLegacyReconnectableClose policy below (CYP-839) — deliberately the OPPOSITE polarity, correct per trust
// context (see its note). The two policies are NEVER merged; each has ONE home.

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

// ══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// CYP-839 — the LOCAL-HUB (loopback) close policy: DENY-{1008}. Deliberately the OPPOSITE polarity to isTerminalClose,
// and CORRECT for its trust context (coordinator-confirmed Option A, 2026-07-27): the local hub is a TRUSTED loopback
// connection, NOT a security surface. An unknown/unrecognized close there is a transient blip (server restart /
// dev-server reload) → reconnect; only 1008 (auth revoked) is terminal. Contrast isTerminalClose (A5 REMOTE = security
// surface → fail-closed, unknown→terminal): raising the local-hub sites to fail-closed would break local resilience with
// NO security gain (a real security close carries 1008 either way; the local hub is not a security boundary). TWO
// policies, one per trust context — NEVER merged. This single-sources the `code === 1008` check the legacy sites
// (reconnectingSocket · App.commCloseToConnection + the CYP-815 status-feed close · eventLogStore) each duplicated (the
// CYP-289 convention-only drift hazard).
/**
 * Local-hub reconnectability: reconnect on EVERYTHING except 1008 (INCLUDING undefined and every unrecognized code).
 * Deny-{1008}, no narrowing — this preserves the exact legacy behavior (Tester2 baseline); the ONLY terminal close on a
 * trusted loopback connection is 1008 (auth revoked).
 */
export function isLegacyReconnectableClose(code: number | undefined): boolean {
  return code !== 1008
}
