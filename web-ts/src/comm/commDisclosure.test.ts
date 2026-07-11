import { describe, it, expect } from 'vitest'
import { composerDisclosure } from './commDisclosure'

describe('composerDisclosure (CYP-407 — three distinct states, never merged)', () => {
  it('readonly when canWrite is false (proactive), even if an error is also set', () => {
    expect(composerDisclosure(false, null)).toBe('readonly')
    expect(composerDisclosure(false, 'comm_send_failed')).toBe('readonly')
  })

  it('denied on an ACL-rejected send', () => {
    expect(composerDisclosure(true, 'comm_send_denied')).toBe('denied')
  })

  it('failed on a generic send error', () => {
    expect(composerDisclosure(true, 'comm_send_failed')).toBe('failed')
  })

  it('ok when writable with no error (canWrite unknown also = ok)', () => {
    expect(composerDisclosure(true, null)).toBe('ok')
    expect(composerDisclosure(null, null)).toBe('ok')
  })
})
