// CYP-733 (Epic CYP-675, Option-A BASIS) — resolve which security tier the RUNNING connection has, so the
// CYP-676 badge can finally be wired. Built against UIUX2's BASIS spec (72b41e68 §2/§6).
//
// THE TIER IS A CLIENT CONSTANT, NOT A SERVER FIELD — and that is the honest part. A browser structurally cannot
// do the native Noise-E2E path (`buildRemoteHubTransport` is null off-JVM, measured by Backend2), so it can never
// reach NATIVE: not as a floor, not as a ceiling. Asking a server "which tier am I on?" would be the wrong shape
// twice over — it would invite a server to claim a tier the client cannot actually have, and a claimed tier is
// exactly what a trust indicator must never rest on. So this maps from what the CLIENT can observe.
//
// NEVER NATIVE. This function has no input that yields `native`, and a test pins that. If web-ts ever gains a
// native transport, that is a deliberate change here — not something a new connection state should be able to
// produce by accident.
//
// FAIL-CLOSED (spec §6): without a live connection there is no running connection to describe, so the tier is
// UNKNOWN — never an optimistic tier for a connection that is not up. The badge renders UNKNOWN as its honest
// resting state; the one thing it must never do is show a stronger tier than the connection has.
import type { RemoteSecurityTier } from './remoteSecurityTierModel'

/** The comm socket's connection state — the app's live-connection signal (CommPanel's `connection`). */
export type ConnectionState = 'live' | 'connecting' | 'offline' | 'revoked'

/**
 * The tier to display for the current connection.
 *
 * `live` → BROWSER_GATEWAY: the SPA is served by the gateway and speaks same-origin `/api`+`/ws` to it, so the
 * gateway terminates the transport encryption and sees traffic in cleartext. Documented weaker, always disclosed.
 *
 * Anything else → UNKNOWN. `connecting` has not established anything yet; `offline`/`revoked` describe a
 * connection that is not carrying traffic. Claiming a tier for a connection that is not up would describe
 * something that does not exist.
 */
export function gatewayTierFor(connection: ConnectionState): RemoteSecurityTier {
  return connection === 'live' ? 'browser-gateway' : 'unknown'
}
