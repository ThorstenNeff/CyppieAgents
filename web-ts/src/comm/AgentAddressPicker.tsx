// CYP-876 (OS-D) — the recipient-addressing control: pick a target agent → resolve its DIRECT spoke (client-side,
// agentAddressing.ts) → jump the composer to that channel. Fail-closed, render ≠ authority:
//  • resolved   → the "DM" action selects the spoke channel (the existing channel-post carries the message).
//  • unreachable → honest "not directly addressable" — NO fabricated channel (new direct link = channel-mgmt/OS-C).
//  • ambiguous  → a visible FLAG (role=alert), the action is DISABLED — NEVER a silent-first pick of a DM route from
//    untrusted data.
import { useState } from 'react'
import { resolveAgentChannel } from './agentAddressing'
import type { Channel } from '../types/generated/contract'

export function AgentAddressPicker({
  agentIds,
  channels,
  onSelectChannel,
}: {
  /** Candidate recipients (the roster agent ids, minus self is the caller's concern). */
  agentIds: readonly string[]
  channels: readonly Channel[]
  /** Jump the composer to a channel (reused for the resolved spoke — no new send path). */
  onSelectChannel: (channelId: string) => void
}) {
  const [target, setTarget] = useState('')
  const resolution = target === '' ? null : resolveAgentChannel(target, channels)

  const address = () => {
    if (resolution?.kind === 'resolved') onSelectChannel(resolution.channelId)
    // unreachable / ambiguous: NO action — the honest state is shown, never a silent route.
  }

  return (
    <div className="agent-address" data-testid="agent-address">
      <label>
        <span>Direktnachricht an</span>
        <select aria-label="Agent adressieren" data-testid="agent-address.select" value={target} onChange={(e) => setTarget(e.target.value)}>
          <option value="">— Agent wählen —</option>
          {agentIds.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
      </label>
      <button type="button" data-testid="agent-address.dm" disabled={resolution?.kind !== 'resolved'} onClick={address}>
        Öffnen
      </button>
      {resolution?.kind === 'unreachable' && (
        <span className="agent-address-unreachable" data-testid="agent-address.unreachable">
          Nicht direkt erreichbar — dafür zuerst einen Kanal anlegen.
        </span>
      )}
      {resolution?.kind === 'ambiguous' && (
        <span className="agent-address-ambiguous" role="alert" data-testid="agent-address.ambiguous">
          Mehrdeutig — mehrere Direktkanäle für diesen Agenten; bitte den Kanal manuell wählen.
        </span>
      )}
    </div>
  )
}
