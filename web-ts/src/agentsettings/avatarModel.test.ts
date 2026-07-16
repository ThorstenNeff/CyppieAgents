// CYP-658 — teeth for the Agent-Avatar honesty core: the fail-closed upload pre-check (type/size gate), the
// DTO-derived stage chain, the preset builders, the same-origin URL builders (never dicebear), and the credit lines.
import { describe, it, expect } from 'vitest'
import {
  AVATAR_STYLES,
  creditLine,
  precheckUpload,
  ALLOWED_UPLOAD_MIME,
  MAX_UPLOAD_BYTES,
  uploadErrorText,
  avatarStage,
  selectedStyleOf,
  presetFor,
  shuffledPreset,
  avatarVersionToken,
  avatarServeUrl,
  avatarPreviewUrl,
} from './avatarModel'
import type { AgentAvatar } from '../types/generated/contract'

describe('AVATAR_STYLES catalog', () => {
  it('is the curated 5, with exactly the 3 CC-BY styles flagged (attribution + suffix)', () => {
    expect(AVATAR_STYLES.map((s) => s.style)).toEqual(['bottts', 'avataaars', 'adventurer', 'big-smile', 'fun-emoji'])
    expect(AVATAR_STYLES.filter((s) => s.ccBy).map((s) => s.style)).toEqual(['adventurer', 'big-smile', 'fun-emoji'])
    // every CC-BY style carries a real license URL (CC BY 4.0 §3(a) requires the URI)
    for (const s of AVATAR_STYLES.filter((s) => s.ccBy)) expect(s.licenseUrl).toContain('creativecommons.org/licenses/by/4.0')
  })
})

describe('creditLine', () => {
  it('credits all styles; only CC-BY gets the "(bearbeitet)" modified suffix', () => {
    const cc = AVATAR_STYLES.find((s) => s.style === 'big-smile')!
    const free = AVATAR_STYLES.find((s) => s.style === 'bottts')!
    expect(creditLine(cc)).toBe('Big Smile von Ashley Seo · CC BY 4.0 (bearbeitet)')
    expect(creditLine(free)).toBe('Bottts von Pablo Stanley · Frei für privat & kommerziell')
    expect(creditLine(free)).not.toContain('bearbeitet') // courtesy credit, not a CC-BY modification
  })
})

describe('precheckUpload (fail-closed gate)', () => {
  it('allows only PNG/JPEG within the cap', () => {
    expect(precheckUpload('image/png', 1024)).toBe('ok')
    expect(precheckUpload('image/jpeg', MAX_UPLOAD_BYTES)).toBe('ok') // exactly at the cap is allowed
    expect(precheckUpload('IMAGE/PNG', 1024)).toBe('ok') // case-insensitive
  })
  it('rejects a disallowed type → `type` (fail-closed; never persisted)', () => {
    // RED if the MIME allow-list is dropped: an SVG/webp/gif would sneak through.
    expect(precheckUpload('image/svg+xml', 1024)).toBe('type')
    expect(precheckUpload('image/webp', 1024)).toBe('type')
    expect(precheckUpload('image/gif', 1024)).toBe('type')
    expect(precheckUpload('', 1024)).toBe('type')
  })
  it('rejects oversize → `size`', () => {
    // RED if the size comparison flips: a 5MB+1 blob would upload.
    expect(precheckUpload('image/png', MAX_UPLOAD_BYTES + 1)).toBe('size')
  })
  it('type is checked BEFORE size (an oversize wrong-type reports `type`)', () => {
    expect(precheckUpload('image/svg+xml', MAX_UPLOAD_BYTES + 1)).toBe('type')
  })
  it('the allow-list is exactly PNG/JPEG (no webp, no SVG)', () => {
    expect([...ALLOWED_UPLOAD_MIME].sort()).toEqual(['image/jpeg', 'image/jpg', 'image/png'])
  })
})

describe('uploadErrorText', () => {
  it('maps each kind; size names the limit', () => {
    expect(uploadErrorText('type')).toContain('PNG')
    expect(uploadErrorText('size')).toContain('5 MB')
    expect(uploadErrorText('generic')).toBe('Hochladen fehlgeschlagen.')
  })
})

describe('avatarStage (DTO-derived fallback chain)', () => {
  const upload: AgentAvatar = { ref: 'blob-1', type: 'upload' }
  const preset: AgentAvatar = { style: 'bottts', seed: 'x', type: 'preset' }
  it('upload→image, preset→preset, named→initials, blank→color', () => {
    expect(avatarStage(upload, 'Frontend')).toBe('image')
    expect(avatarStage(preset, 'Frontend')).toBe('preset')
    expect(avatarStage(null, 'Frontend')).toBe('initials')
    expect(avatarStage(null, '   ')).toBe('color') // blank name → colour disc, no initials
    expect(avatarStage(undefined, '')).toBe('color')
  })
  it('selectedStyleOf reflects a preset only', () => {
    expect(selectedStyleOf(preset)).toBe('bottts')
    expect(selectedStyleOf(upload)).toBeNull()
    expect(selectedStyleOf(null)).toBeNull()
  })
})

describe('preset builders', () => {
  it('presetFor seeds by the agent id (deterministic default)', () => {
    expect(presetFor('adventurer', 'frontend')).toEqual({ style: 'adventurer', seed: 'frontend', type: 'preset' })
  })
  it('shuffledPreset re-rolls the seed within the style (caller-supplied nonce)', () => {
    expect(shuffledPreset('adventurer', 'frontend', 42)).toEqual({ style: 'adventurer', seed: 'frontend-42', type: 'preset' })
  })
})

describe('same-origin image URLs (never dicebear)', () => {
  it('version token: upload→ref, preset→`style-seed`', () => {
    expect(avatarVersionToken({ ref: 'blob-9', type: 'upload' })).toBe('blob-9')
    expect(avatarVersionToken({ style: 'bottts', seed: 's1', type: 'preset' })).toBe('bottts-s1')
  })
  it('serve URL is the same-origin route with a cache-bust token, ids encoded', () => {
    const u = avatarServeUrl('http://h:8787', 'front/end', { style: 'bottts', seed: 's', type: 'preset' })
    expect(u).toBe('http://h:8787/api/agents/front%2Fend/avatar?v=bottts-s')
    expect(u).not.toContain('dicebear')
  })
  it('preview URL carries style+seed, encoded, same-origin', () => {
    const u = avatarPreviewUrl('http://h:8787', 'a b', 'fun-emoji', 'a b-3')
    expect(u).toBe('http://h:8787/api/agents/a%20b/avatar/preview?style=fun-emoji&seed=a%20b-3')
    expect(u).not.toContain('dicebear')
  })
})
