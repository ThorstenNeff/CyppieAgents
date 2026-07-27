// CYP-822 (CYP-807-A1) — test double for the connect-progression event source. NOT a *.test.ts, so vitest never runs it
// as a suite. Mirrors net/testing/fakeSocket.ts: each driver method emits ONE RemoteConnEvent to the subscribed sink and
// records it, so a test drives idle → … → connected (and each failure arm) deterministically and asserts the machine's
// visible state. The real connector (CYP-807-A5 / Backend2 CYP-824) implements the same event-source shape over the wire.
import type { HubIssuerTrust } from '../../connector/issuerTrustModel'
import { issuerTrustToPreVerdict } from '../../connector/issuerPreVerdict'
import type { IssuerConnectVerdict, RemoteConnEvent, TierGate } from '../remoteConnState'

export class FakeRemoteConnector {
  private sink: ((event: RemoteConnEvent) => void) | null = null
  /** Every event this connector emitted, in order — lets a test assert the driven progression. */
  readonly emitted: RemoteConnEvent[] = []

  /** Wire the connector to a consumer (typically the machine's `send`). Returns an unsubscribe. */
  subscribe(sink: (event: RemoteConnEvent) => void): () => void {
    this.sink = sink
    return () => {
      if (this.sink === sink) this.sink = null
    }
  }

  private emit(event: RemoteConnEvent): void {
    this.emitted.push(event)
    this.sink?.(event)
  }

  // --- drivers (each mirrors one observed wire transition) ---
  dial(): void {
    this.emit({ kind: 'dial' })
  }
  refuse(): void {
    this.emit({ kind: 'dialRefused' })
  }
  openHandshake(): void {
    this.emit({ kind: 'handshakeOpen' })
  }
  failHandshake(): void {
    this.emit({ kind: 'handshakeFailed' })
  }
  completeHandshake(): void {
    this.emit({ kind: 'handshakeOk' })
  }
  /** Emit a trust-check resolution with an explicit sealed verdict (bypassing the producer) — lets a test drive an
   *  arbitrary verdict directly. tierGate defaults to 'ok'. */
  evaluateTrust(verdict: IssuerConnectVerdict, tierGate: TierGate = 'ok'): void {
    this.emit({ kind: 'trustEvaluated', verdict, tierGate })
  }
  /** Convenience: derive the verdict from a raw HubIssuerTrust via the ONE issuer→verdict producer,
   *  issuerTrustToPreVerdict (CYP-837 single-source — the client goes through exactly this path). */
  evaluateTrustFromIssuer(issuer: HubIssuerTrust | null | undefined, tierGate: TierGate = 'ok'): void {
    this.evaluateTrust(issuerTrustToPreVerdict(issuer), tierGate)
  }
  /** Drive a drop of an established connection. terminal=true → the terminal `lost` phase (→ failure region);
   *  terminal=false → the transient `reconnecting` phase (polite/retryable, not a failure arm). */
  drop(terminal: boolean): void {
    this.emit({ kind: 'dropped', terminal })
  }
  reset(): void {
    this.emit({ kind: 'reset' })
  }
}
