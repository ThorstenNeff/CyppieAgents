import { describe, it, expect } from 'vitest'
import { enforcedValue, aclCellState, isPoLockoutChange, presetDiff, pendingKey, type PendingAcl } from './aclModel'
import type { AclEntry } from '../types/generated/contract'

const e = (channelId: string, agentId: string, canRead: boolean, canWrite: boolean): AclEntry => ({ channelId, agentId, canRead, canWrite })

describe('aclModel (CYP-407 honesty rules)', () => {
  it('enforcedValue: no entry → both false', () => {
    expect(enforcedValue([], 'c', 'a')).toEqual({ canRead: false, canWrite: false })
  })

  it('conflict = deny-wins (AND across duplicate entries)', () => {
    const entries = [e('c', 'a', true, true), e('c', 'a', false, true)]
    expect(enforcedValue(entries, 'c', 'a')).toEqual({ canRead: false, canWrite: true })
  })

  it('cell is NON-OPTIMISTIC: checked = ENFORCED value, pending is a separate flag', () => {
    const entries = [e('c', 'a', false, false)]
    const pending: PendingAcl = new Map([[pendingKey('c', 'a', 'read'), true]])
    const cell = aclCellState('c', 'a', entries, pending, ['a'], 'po')
    expect(cell.read.checked).toBe(false) // NOT the optimistic pending click
    expect(cell.read.pending).toBe(true)
    expect(cell.write.pending).toBe(false)
  })

  it('member vs non-member (grantable, not inert)', () => {
    expect(aclCellState('c', 'a', [], new Map(), ['a'], null).member).toBe(true)
    expect(aclCellState('c', 'x', [], new Map(), ['a'], null).member).toBe(false)
  })

  it('poCritical only for the PO agent', () => {
    expect(aclCellState('c', 'po', [], new Map(), ['po'], 'po').poCritical).toBe(true)
    expect(aclCellState('c', 'a', [], new Map(), ['a'], 'po').poCritical).toBe(false)
  })

  it('isPoLockoutChange: removing PO read → true; granting / non-PO / write → false', () => {
    expect(isPoLockoutChange('po', 'read', false, 'po')).toBe(true)
    expect(isPoLockoutChange('po', 'read', true, 'po')).toBe(false) // granting is safe
    expect(isPoLockoutChange('po', 'write', false, 'po')).toBe(false) // write is not the lockout axis
    expect(isPoLockoutChange('a', 'read', false, 'po')).toBe(false) // not the PO
  })

  it('presetDiff returns only the cells that change (non-atomic; empty = nothing, never a silent done)', () => {
    const entries = [e('c', 'a', true, true)]
    const target = [
      { channelId: 'c', agentId: 'a', canRead: true, canWrite: true }, // unchanged
      { channelId: 'c', agentId: 'b', canRead: true, canWrite: false }, // changes
    ]
    expect(presetDiff(entries, target)).toEqual([{ channelId: 'c', agentId: 'b', canRead: true, canWrite: false }])
  })
})
