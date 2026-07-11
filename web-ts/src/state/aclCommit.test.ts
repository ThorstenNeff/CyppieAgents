import { describe, it, expect, vi } from 'vitest'
import { commitAclChange, aclRejectMessage, type AclCommitHooks } from './aclCommit'
import { RestError } from '../net/rest'
import type { HubRepo } from './restRepo'
import type { AclEntry } from '../types/generated/contract'

const entry: AclEntry = { channelId: 'po-frontend', agentId: 'po', canRead: false, canWrite: true }

const hooks = (): AclCommitHooks => ({ markPending: vi.fn(), clearPending: vi.fn(), setError: vi.fn() })
const repoWith = (putAcl: HubRepo['putAcl']): HubRepo => ({
  putAcl,
  fetchAgents: vi.fn(),
  fetchChannels: vi.fn(),
  fetchAcl: vi.fn(),
  requestMode: vi.fn(),
  getMessages: vi.fn(),
  postMessage: vi.fn(),
  setLifecycle: vi.fn(),
})

describe('commitAclChange (CYP-435 — pending lifecycle)', () => {
  it('on a REJECT (no echo will come) clears the pending it set and surfaces the reason', async () => {
    const h = hooks()
    const repo = repoWith(vi.fn().mockRejectedValue(new RestError(409, 'PUT', '/api/acl', 'po_lockout_protected')))
    await commitAclChange(repo, h, entry, ['read'])
    expect(h.markPending).toHaveBeenCalledWith('po-frontend', 'po', 'read', false)
    // the fix: the same dim is cleared, so the switch does not spin forever
    expect(h.clearPending).toHaveBeenCalledWith('po-frontend', 'po', 'read')
    expect(h.setError).toHaveBeenLastCalledWith('Vom Hub abgelehnt (PO-Aussperrschutz aktiv).')
  })

  it('on SUCCESS does NOT clear pending (the AclEvent echo does that) and sets no error', async () => {
    const h = hooks()
    const repo = repoWith(vi.fn().mockResolvedValue(entry))
    await commitAclChange(repo, h, entry, ['read'])
    expect(h.markPending).toHaveBeenCalledTimes(1)
    expect(h.clearPending).not.toHaveBeenCalled()
    expect(h.setError).toHaveBeenCalledTimes(1) // only the initial reset to null
    expect(h.setError).toHaveBeenCalledWith(null)
  })

  it('aclRejectMessage distinguishes the 409 lockout from a generic failure', () => {
    expect(aclRejectMessage(new RestError(409, 'PUT', '/api/acl', ''))).toContain('PO-Aussperrschutz')
    expect(aclRejectMessage(new RestError(500, 'PUT', '/api/acl', ''))).toContain('fehlgeschlagen')
    expect(aclRejectMessage(new Error('network'))).toContain('fehlgeschlagen')
  })
})
