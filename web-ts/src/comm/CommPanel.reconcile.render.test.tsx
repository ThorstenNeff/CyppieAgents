// @vitest-environment jsdom
// CYP-876 (merge-reconciliation) — after the rebase onto develop, OS-A (CYP-868 orchestration render: kind badges +
// reply threading in the timeline) and OS-D (CYP-876 AgentAddressPicker mount, above the channel nav) live in ONE
// CommPanel for the FIRST time (875/876 branched before OS-A, so no prior test renders both). This pins exactly that
// reconciliation: both coexist, and the picker mount does not displace the message loop. Not composition-theater — it
// guards the specific two-concern coexistence this rebase created.
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CommPanel, type CommPanelProps } from './CommPanel'
import type { Channel, DeliveredMessage, MessageMeta } from '../types/generated/contract'

afterEach(cleanup)

const channels: Channel[] = [{ id: 'c', name: 'C', kind: 'DIRECT', members: ['po', 'frontend'] }]
const dm = (id: string, meta?: MessageMeta | null): DeliveredMessage => ({
  message: { id, channelId: 'c', from: 'po', body: `b-${id}`, ts: 0, ...(meta !== undefined ? { meta } : {}) },
})
const base: CommPanelProps = {
  channels, selectedChannelId: 'c', onSelectChannel: () => {}, messages: [], senderRole: () => null,
  connection: 'live', canWrite: true, sendError: null, onSend: () => {}, historySize: () => 20,
}

describe('CYP-876 — CommPanel reconciliation: OS-A render + OS-D picker coexist', () => {
  it('★ with addressableAgents SET and TASK/reply meta on messages, the picker AND the kind-badge + reply-tree all render', () => {
    // MUT: drop the OS-D picker mount → agent-address gone → reds. MUT: drop the OS-A kind/reply render → the
    // badge/replyTo gone → reds. Either regression in the reconciled CommPanel is caught here.
    const { getByTestId } = render(
      <CommPanel {...base} addressableAgents={['frontend', 'backend']} messages={[dm('p'), dm('r', { kind: 'TASK', inReplyTo: 'p' })]} />,
    )
    // OS-D: the addressing picker mounts (above the channel nav)
    expect(getByTestId('agent-address')).toBeTruthy()
    // OS-A: the orchestration render is present in the timeline
    expect(getByTestId('comm.message.r.kind').textContent).toBe('TASK')
    expect(getByTestId('comm.message.r.replyTo').getAttribute('data-reply-to')).toBe('p')
    // …and the picker mount did NOT displace the message loop — both messages still render
    expect(getByTestId('comm.message.p')).toBeTruthy()
    expect(getByTestId('comm.message.r')).toBeTruthy()
  })
})
