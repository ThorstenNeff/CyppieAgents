// CYP-876 (OS-D, Epic CYP-867) — client-side recipient addressing: resolve a target agentId to its DIRECT/spoke
// channel. web-ts-only (Dev5 verdict, PO2-ratified): a spoke = a DIRECT channel whose members include the target;
// the visible channels are already ACL-filtered to the PO/operator, so the target's DIRECT channel IS the PO↔target
// spoke. No :server primitive.
//
// Fail-closed, render ≠ authority (PO2 decisions):
//  • exactly one DIRECT spoke → resolved.
//  • MORE than one → AMBIGUOUS: null + flag, NEVER silent-first. A DM-routing decision derived from untrusted server
//    data must not be guessed away; the ambiguity is itself a signal (possibly corrupt/untrusted data) and is surfaced.
//  • none → UNREACHABLE: honestly "not directly addressable" — NO fabricated channel (a new direct link is OS-C
//    channel-creation, not this).
import type { Channel } from '../types/generated/contract'

export type AgentAddress =
  | { readonly kind: 'resolved'; readonly channelId: string }
  | { readonly kind: 'ambiguous'; readonly channelIds: readonly string[] }
  | { readonly kind: 'unreachable' }

/**
 * Resolve `agentId` to its DIRECT spoke channel among the (ACL-filtered) `channels`. Never fabricates and never
 * silently picks: 1 spoke → resolved; >1 → ambiguous (all candidates, for the flag); 0 → unreachable.
 */
export function resolveAgentChannel(agentId: string, channels: readonly Channel[]): AgentAddress {
  const spokes = channels.filter((c) => c.kind === 'DIRECT' && c.members.includes(agentId))
  if (spokes.length === 1) return { kind: 'resolved', channelId: spokes[0].id }
  if (spokes.length > 1) return { kind: 'ambiguous', channelIds: spokes.map((c) => c.id) }
  return { kind: 'unreachable' }
}
