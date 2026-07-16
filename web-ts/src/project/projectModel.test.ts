// CYP-651 — the pure project policy. Teeth pin the DESTRUCTIVE-DELETE gate (delete fires only when not-blocked AND
// name-echo matches — the review-focus tooth), the blocked-reason order (last before active), and the code mapping.
import { describe, it, expect } from 'vitest'
import { deleteBlockedReason, nameEchoMatches, canFireProjectDelete, switchIsNoop, projectMutationMessage } from './projectModel'

describe('deleteBlockedReason (server-order: last before active)', () => {
  it('a lone project → last (it is both last AND active; server reports last_project first)', () => {
    expect(deleteBlockedReason('p1', 'p1', 1)).toBe('last')
  })
  it('the active project (with others present) → active (switch away first)', () => {
    expect(deleteBlockedReason('p1', 'p1', 3)).toBe('active')
  })
  it('a non-active project (with others present) → null (deletable)', () => {
    expect(deleteBlockedReason('p2', 'p1', 3)).toBeNull()
  })
})

describe('nameEchoMatches (client UX confirm — no server token)', () => {
  it('exact trimmed match arms the delete; anything else does not', () => {
    expect(nameEchoMatches('My Project', 'My Project')).toBe(true)
    expect(nameEchoMatches('  My Project  ', 'My Project')).toBe(true)
    expect(nameEchoMatches('my project', 'My Project')).toBe(false) // case matters
    expect(nameEchoMatches('', 'My Project')).toBe(false)
    expect(nameEchoMatches('My Projec', 'My Project')).toBe(false)
  })
})

describe('canFireProjectDelete (the destructive-delete GATE)', () => {
  const base = { projectId: 'p2', activeProjectId: 'p1', projectCount: 3, projectName: 'Beta' }

  it('fires ONLY when not-blocked AND the name-echo matches', () => {
    expect(canFireProjectDelete({ ...base, nameEcho: 'Beta' })).toBe(true)
  })
  it('does NOT fire without a matching name-echo (delete-without-confirm → no call)', () => {
    // RED if the name-echo is ever dropped from the gate — a hard delete must require the typed confirmation.
    expect(canFireProjectDelete({ ...base, nameEcho: '' })).toBe(false)
    expect(canFireProjectDelete({ ...base, nameEcho: 'wrong' })).toBe(false)
  })
  it('does NOT fire on the active project even with a matching name-echo (must switch away first)', () => {
    expect(canFireProjectDelete({ ...base, projectId: 'p1', nameEcho: 'Beta' })).toBe(false)
  })
  it('does NOT fire on the last project', () => {
    expect(canFireProjectDelete({ ...base, projectCount: 1, nameEcho: 'Beta' })).toBe(false)
  })
})

describe('switchIsNoop', () => {
  it('true only when the target is already active', () => {
    expect(switchIsNoop('p1', 'p1')).toBe(true)
    expect(switchIsNoop('p2', 'p1')).toBe(false)
  })
})

describe('projectMutationMessage', () => {
  it('maps the guard codes; unknown → a generic failure (never a fabricated success)', () => {
    expect(projectMutationMessage('active_project_protected')).toContain('erst wechseln')
    expect(projectMutationMessage('last_project')).toContain('letzte')
    expect(projectMutationMessage('project_not_found')).toContain('schon gelöscht')
    expect(projectMutationMessage('project_exists')).toContain('existiert')
    expect(projectMutationMessage('weird_code')).toBe('Aktion fehlgeschlagen.')
  })
})
