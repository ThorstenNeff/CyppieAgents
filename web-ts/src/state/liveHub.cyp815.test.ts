// CYP-815 (regression tooth for the HIGH bug: OneWayFeed silently SWALLOWED onClose). Dev5's fix forwards onClose on the
// four read-only status feeds (lifecycle/token-usage/busy-state/terminal-state), so a 1008 (auth revoked) surfaces the
// VISIBLE revoked signal instead of freezing the run-state/token/busy/terminal indicators while they still claim "running".
//
// PO1's four requirements, all satisfied here:
//  (a) RED-BEFORE-FIX: driven through the REAL feed wiring + the OneWayFeed — reverting the `onClose: opts.onClose` forward
//      (the pre-fix swallow) makes the 1008 never reach onStatusClose → `visible` stays empty → these tests RED.
//  (b) MUT swallow-back: same mutation (remove the forward) reddens.
//  (c) ALL FOUR status feeds — each driven independently + an all-four assertion, so a fix wiring only one leaves the
//      other three's silent-freeze uncaught.
//  (d) checks the VISIBLE signal (the 'revoked' outcome), NOT merely that onClose fired.
import { describe, it, expect } from 'vitest'
import { startLiveHub } from './liveHub'
import { singleHubConfig } from './hubConfig'
import { FakeSocketHub } from '../net/testing/fakeSocket'

const config = singleHubConfig({ endpoint: { apiBase: 'http://x', wsBase: 'ws://x' }, token: 'tok', operator: true })
const socketFor = (hub: FakeSocketHub, path: string) => {
  const s = hub.sockets.find((sk) => sk.url.includes(path))
  if (s === undefined) throw new Error(`no socket for ${path}`)
  return s
}
const STATUS_FEEDS = ['/ws/terminal-state', '/ws/lifecycle', '/ws/busy-state', '/ws/token-usage'] as const

// App's onStatusClose mapping, faithfully: a 1008 → the VISIBLE revoked connection; any OTHER close → nothing (transient,
// self-heals via the feed's reconnect — Dev5's design). We assert the VISIBLE outcome, driven through the real wiring.
const captureVisible = (sink: string[]) => (code?: number) => {
  if (code === 1008) sink.push('revoked')
}
const start = (hub: FakeSocketHub, onStatusClose: (code?: number) => void) =>
  startLiveHub(config, { onCommEvent: () => {}, onTerminalControl: () => {}, onStatusClose }, { factory: hub.factory, schedule: hub.runNow })

describe('CYP-815 — a 1008 revoke on ANY read-only status feed surfaces the VISIBLE revoked signal (no silent freeze)', () => {
  for (const path of STATUS_FEEDS) {
    it(`★ 1008 close on ${path} → visible revoked (RED before the OneWayFeed onClose-forward fix)`, () => {
      const hub = new FakeSocketHub()
      const visible: string[] = []
      start(hub, captureVisible(visible))
      socketFor(hub, path).emitClose(1008)
      expect(visible).toEqual(['revoked']) // the VISIBLE outcome, not merely "onClose fired"
    })
  }

  it('★ ALL FOUR status feeds surface it — a fix wiring only one leaves the other three silently frozen (c)', () => {
    const hub = new FakeSocketHub()
    const visible: string[] = []
    start(hub, captureVisible(visible))
    for (const path of STATUS_FEEDS) socketFor(hub, path).emitClose(1008)
    expect(visible).toEqual(['revoked', 'revoked', 'revoked', 'revoked'])
  })

  it('★ (5th pin — Dev5 self-heal) a NON-1008 transient close sets NO sticky revoked/offline on the status feeds', () => {
    const hub = new FakeSocketHub()
    const visible: string[] = []
    start(hub, captureVisible(visible))
    socketFor(hub, '/ws/lifecycle').emitClose(1006) // transient, not a revoke
    expect(visible).toEqual([]) // the transient drop self-heals via reconnect — no sticky signal
  })
})
