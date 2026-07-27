// CYP-815 (regression tooth for the HIGH bug: OneWayFeed silently SWALLOWED onClose). Dev5's fix forwards onClose on the
// read-only status feed, so a 1008 (auth revoked) surfaces the VISIBLE revoked signal instead of freezing the
// run-state/token/busy/terminal indicators while they still claim "running".
//
// CYP-844: the four separate status feeds are now MUXED into the single /ws/status socket — so this regression
// consolidates onto ONE feed, and matters MORE (that one socket's onClose now covers all four indicators at once). The
// four requirements still hold, retargeted to /ws/status:
//  (a) RED-BEFORE-FIX: driven through the REAL feed wiring + the OneWayFeed — reverting the `onClose: opts.onClose` forward
//      (the pre-fix swallow) makes the 1008 never reach onStatusClose → `visible` stays empty → these tests RED.
//  (b) MUT swallow-back: same mutation (remove the forward) reddens.
//  (c) the muxed feed covers ALL FOUR indicators — a swallow here silently freezes every one of them.
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

// App's onStatusClose mapping, faithfully: a 1008 → the VISIBLE revoked connection; any OTHER close → nothing (transient,
// self-heals via the feed's reconnect — Dev5's design). We assert the VISIBLE outcome, driven through the real wiring.
const captureVisible = (sink: string[]) => (code?: number) => {
  if (code === 1008) sink.push('revoked')
}
const start = (hub: FakeSocketHub, onStatusClose: (code?: number) => void) =>
  startLiveHub(config, { onCommEvent: () => {}, onTerminalControl: () => {}, onStatusClose }, { factory: hub.factory, schedule: hub.runNow })

describe('CYP-815/CYP-844 — a 1008 revoke on the muxed /ws/status feed surfaces the VISIBLE revoked signal (no silent freeze)', () => {
  it('★ 1008 close on /ws/status → visible revoked (RED before the OneWayFeed onClose-forward fix)', () => {
    const hub = new FakeSocketHub()
    const visible: string[] = []
    start(hub, captureVisible(visible))
    socketFor(hub, '/ws/status').emitClose(1008)
    expect(visible).toEqual(['revoked']) // the VISIBLE outcome, not merely "onClose fired"
  })

  it('★ the muxed feed carries all four indicators — a swallow here freezes every one of them at once (c)', () => {
    // One socket now covers run-state/token/busy/terminal; if its onClose is swallowed, ALL freeze on a revoke.
    const hub = new FakeSocketHub()
    const visible: string[] = []
    start(hub, captureVisible(visible))
    socketFor(hub, '/ws/status').emitClose(1008)
    expect(visible).toEqual(['revoked'])
  })

  it('★ (self-heal) a NON-1008 transient close sets NO sticky revoked/offline on /ws/status', () => {
    const hub = new FakeSocketHub()
    const visible: string[] = []
    start(hub, captureVisible(visible))
    socketFor(hub, '/ws/status').emitClose(1006) // transient, not a revoke
    expect(visible).toEqual([]) // the transient drop self-heals via reconnect — no sticky signal
  })
})
