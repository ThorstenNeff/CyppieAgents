import { describe, it, expect } from 'vitest'
import { statusDotSpec, dotRoleVar, lifecycleLabel } from './lifecycleStatus'

describe('statusDotSpec (CYP-431, port of CYP-396)', () => {
  it('a pending request is the NEUTRAL filled dot, never a resolved colour', () => {
    // pending overrides the state → no resolved colour leaks before the server confirms (non-optimistic)
    expect(statusDotSpec('RUNNING', true)).toEqual({ shape: 'fill', role: 'neutral' })
    expect(statusDotSpec('ERROR', true)).toEqual({ shape: 'fill', role: 'neutral' })
  })

  it('resolved states: RUNNING=fill/primary, STOPPED=fill/outline, ERROR=fill/error', () => {
    expect(statusDotSpec('RUNNING', false)).toEqual({ shape: 'fill', role: 'primary' })
    expect(statusDotSpec('STOPPED', false)).toEqual({ shape: 'fill', role: 'outline' })
    expect(statusDotSpec('ERROR', false)).toEqual({ shape: 'fill', role: 'error' })
  })

  it('UNKNOWN is a RING (different axis from STOPPED), role outline (≥3:1), never a pale disc', () => {
    expect(statusDotSpec('UNKNOWN', false)).toEqual({ shape: 'ring', role: 'outline' })
  })
})

describe('dotRoleVar — the maritime token per role', () => {
  it('maps roles to their tokens; neutral is onSurfaceVariant (distinct from RUNNING=primary)', () => {
    expect(dotRoleVar('primary')).toBe('var(--md-sys-color-primary)')
    expect(dotRoleVar('outline')).toBe('var(--md-sys-color-outline)')
    expect(dotRoleVar('error')).toBe('var(--md-sys-color-error)')
    expect(dotRoleVar('neutral')).toBe('var(--md-sys-color-on-surface-variant)')
  })
})

describe('lifecycleLabel — honest text (pending shows a transient, never a resolved label)', () => {
  it('a pending request shows its transient label regardless of the underlying state', () => {
    expect(lifecycleLabel('RUNNING', 'restart')).toBe('Neustart…')
    expect(lifecycleLabel('STOPPED', 'start')).toBe('Startet…')
    expect(lifecycleLabel('RUNNING', 'stop')).toBe('Stoppt…')
  })
  it('resolved states map to their labels', () => {
    expect(lifecycleLabel('RUNNING', undefined)).toBe('Aktiv')
    expect(lifecycleLabel('STOPPED', undefined)).toBe('Gestoppt')
    expect(lifecycleLabel('ERROR', undefined)).toBe('Fehler')
    expect(lifecycleLabel('UNKNOWN', undefined)).toBe('Unbekannt')
  })
})
