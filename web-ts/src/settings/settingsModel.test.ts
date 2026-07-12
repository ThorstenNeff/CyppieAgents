import { describe, it, expect } from 'vitest'
import { RestError } from '../net/rest'
import { canSaveRepo, repoSaveRejectMessage } from './settingsModel'

const rejectWith = (code: string) =>
  new RestError(400, 'PUT', '/api/config/repo', JSON.stringify({ error: { code, message: 'x' } }))

describe('canSaveRepo (CYP-453 — operator ∧ non-empty url)', () => {
  it('needs an operator', () => {
    expect(canSaveRepo(false, 'git@x')).toBe(false)
  })
  it('needs a non-blank url', () => {
    expect(canSaveRepo(true, '')).toBe(false)
    expect(canSaveRepo(true, '   ')).toBe(false)
  })
  it('operator + url → savable', () => {
    expect(canSaveRepo(true, 'git@github.com:o/r.git')).toBe(true)
  })
})

describe('repoSaveRejectMessage (CYP-453 — server-authoritative)', () => {
  it('invalid_repo_url → the specific invalid-URL message', () => {
    expect(repoSaveRejectMessage(rejectWith('invalid_repo_url'))).toBe('Ungültige Repository-URL')
  })
  it('any other code / non-RestError → the generic failure (never a wrong specific claim)', () => {
    expect(repoSaveRejectMessage(rejectWith('boom'))).toBe('Speichern fehlgeschlagen')
    expect(repoSaveRejectMessage(new Error('network'))).toBe('Speichern fehlgeschlagen')
  })
})
