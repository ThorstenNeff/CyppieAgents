import { describe, it, expect } from 'vitest'
import { RestError } from '../net/rest'
import {
  ROLE_OPTIONS,
  roleLabel,
  DEFAULT_WORKTREE_FATE,
  removeConfirmLabel,
  worktreeDeleteWarning,
  addRejectMessage,
  editRejectMessage,
  removeRejectMessage,
} from './agentMgmtModel'

/** Build a RestError whose body is the server's `{ error: { code } }` envelope (Spec 02 §7). */
const rejectWith = (code: string) => new RestError(409, 'POST', '/api/agents', JSON.stringify({ error: { code, message: 'x' } }))

describe('role options (CYP-450 — the picker offers all three contract roles)', () => {
  it('PO / WORKER / PRODUCT_LEAD with their DE labels', () => {
    expect(ROLE_OPTIONS).toEqual(['PO', 'WORKER', 'PRODUCT_LEAD'])
    expect([roleLabel('PO'), roleLabel('WORKER'), roleLabel('PRODUCT_LEAD')]).toEqual(['PO', 'Worker', 'Product Lead'])
  })
})

describe('worktree fate (CYP-450 — the non-destructive choice is the default, spec §3.3)', () => {
  it('defaults to keep', () => {
    expect(DEFAULT_WORKTREE_FATE).toBe('keep')
  })
  it('the confirm wording follows the fate (keep = entfernen, delete = endgültig löschen)', () => {
    expect(removeConfirmLabel('keep')).toBe('Agent entfernen')
    expect(removeConfirmLabel('delete')).toBe('Endgültig löschen')
  })
  it('the data-loss warning names the worktree', () => {
    expect(worktreeDeleteWarning('backend')).toContain('backend')
    expect(worktreeDeleteWarning('backend')).toContain('unwiderbringlich')
  })
})

describe('add rejects — server-authoritative (CYP-450, spec §2 / tooth 9)', () => {
  it('agent_exists → id-collision message', () => {
    expect(addRejectMessage(rejectWith('agent_exists'))).toContain('existiert bereits')
  })
  it('po_already_exists → only-one-PO message', () => {
    expect(addRejectMessage(rejectWith('po_already_exists'))).toContain('bereits einen PO')
  })
  it('an unknown code / non-RestError → the generic create-failed (never a wrong specific claim)', () => {
    expect(addRejectMessage(rejectWith('invalid_agent'))).toBe('Anlegen fehlgeschlagen')
    expect(addRejectMessage(new Error('network'))).toBe('Anlegen fehlgeschlagen')
  })
})

describe('edit rejects — the CYP-101 split: po_already_exists ≠ last_po (tooth 7)', () => {
  it('po_already_exists = PO taken by another → "belegt"', () => {
    expect(editRejectMessage(rejectWith('po_already_exists'))).toContain('belegt')
  })
  it('last_po = the only PO giving up the role → "abgeben" (a DIFFERENT message)', () => {
    expect(editRejectMessage(rejectWith('last_po'))).toContain('abgeben')
  })
  it('the two are never the same text (the whole point of CYP-101)', () => {
    expect(editRejectMessage(rejectWith('po_already_exists'))).not.toBe(editRejectMessage(rejectWith('last_po')))
  })
  it('unknown → generic save-failed', () => {
    expect(editRejectMessage(rejectWith('boom'))).toBe('Speichern fehlgeschlagen')
  })
})

describe('remove rejects — last-PO guard is advisory, server is the guard (tooth 4)', () => {
  it('last_po → the undeletable-only-PO message', () => {
    expect(removeRejectMessage(rejectWith('last_po'))).toContain('einzige PO')
    expect(removeRejectMessage(rejectWith('last_po'))).toContain('entfernt')
  })
  it('unknown → generic remove-failed', () => {
    expect(removeRejectMessage(rejectWith('nope'))).toBe('Entfernen fehlgeschlagen')
  })
})
