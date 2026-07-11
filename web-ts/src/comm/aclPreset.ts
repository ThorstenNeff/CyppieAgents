// CYP-425 (App-Assembly, W9 dialog wiring) — the "Hub-and-Spoke" ACL preset target (Spec 02 §6.2 / 03 S7:
// "Preset wiederherstellbar"). The default topology is: within each channel, every member may read AND write
// its own channel. That is exactly {canRead:true, canWrite:true} for each (channel, member) cell — Hub-and-Spoke
// falls out of the membership, no special-casing. Pure so the preview/diff (presetDiff) stays testable and the
// apply stays NON-ATOMIC (per-cell PUTs with N/M progress), never a silent "done".
import type { Channel } from '../types/generated/contract'
import type { AclChange } from './aclModel'

export function hubAndSpokeTarget(channels: readonly Channel[]): AclChange[] {
  const target: AclChange[] = []
  for (const ch of channels) {
    for (const agentId of ch.members) {
      target.push({ channelId: ch.id, agentId, canRead: true, canWrite: true })
    }
  }
  return target
}
