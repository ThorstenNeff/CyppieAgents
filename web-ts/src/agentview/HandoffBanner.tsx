// CYP-644 (P4, Epic CYP-640) — the handoff / context-lost landmark banner, a persistent WARN-amber strip at the top
// of the agent window (below the header, above the transcript). Parity with CMP HandoffBanners/FrameBanner. WARN,
// never green (a handoff/context-loss is a caution, not success) and never error-red (nothing crashed). Colour is
// never the sole carrier (WCAG 1.4.1): a ▲ glyph + the text carry the meaning.
// CYP-847 (a11y register parity, PL-adjudicated): this banner is the TIER-1 LIVE event-announce (native parity: the
// "live half" of the 2-tier model, CYP-381 §7.1) — an unsolicited-critical event (handoff/context-loss) that must
// INTERRUPT, so role="alert" + explicit aria-live="assertive" (mirrors native's ratified assertive register and the
// web-ts house pattern auth/LoginScreen.tsx:119). No announce-storm: the content is static (since = HH:MM) and the
// banner clears with the state, so the assertive announce fires ONCE on appearance. The TIER-2 durable transcript
// discontinuity landmark stays STATIC (no liveRegion) and is DEFERRED here (CMP defers it too); this is the banner only.
import type { AgentTerminalControlEvent } from '../types/generated/contract'
import { handoffBanner, formatHandoffSince, handoffHolderLabel } from './handoffBannerModel'

export function HandoffBanner({ agentId, control }: { agentId: string; control: AgentTerminalControlEvent | undefined | null }) {
  const banner = handoffBanner(control)
  if (banner === null) return null

  if (banner.kind === 'contextLost') {
    return (
      <div className="handoff-banner" data-testid={`handoff-banner.${agentId}.contextLost`} role="alert" aria-live="assertive">
        <span className="handoff-glyph" aria-hidden="true">
          ▲
        </span>
        <span className="handoff-text">Kontext verloren — ohne vorherige Historie zurückgekehrt.</span>
      </div>
    )
  }

  const holder = handoffHolderLabel(banner.heldBy)
  const since = formatHandoffSince(banner.since)
  return (
    <div
      className="handoff-banner"
      data-testid={`handoff-banner.${agentId}.handoff`}
      role="alert"
      aria-live="assertive"
      aria-label={`Terminal an ${holder} übergeben, seit ${since} — interaktive Sitzung aktiv`}
    >
      <span className="handoff-glyph" aria-hidden="true">
        ▲
      </span>
      <span className="handoff-text">
        Terminal übergeben an {holder} · seit {since}
      </span>
    </div>
  )
}
