import { describe, it, expect } from 'vitest'
import { statusDotSpec, dotRoleVar, lifecycleLabel, lifecycleControlEnabled, lifecycleRejectMessage } from './lifecycleStatus'
import { RestError } from '../net/rest'

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

describe('lifecycleControlEnabled (CYP-445 §5 enablement matrix)', () => {
  it('Start ⇔ NOT running (the §8.4 tooth: disabled while RUNNING, even for an idle operator)', () => {
    expect(lifecycleControlEnabled('start', 'RUNNING', true, false)).toBe(false)
    expect(lifecycleControlEnabled('start', 'STOPPED', true, false)).toBe(true)
    expect(lifecycleControlEnabled('start', 'ERROR', true, false)).toBe(true)
    expect(lifecycleControlEnabled('start', 'UNKNOWN', true, false)).toBe(true)
  })
  it('Stopp ⇔ running', () => {
    expect(lifecycleControlEnabled('stop', 'RUNNING', true, false)).toBe(true)
    expect(lifecycleControlEnabled('stop', 'STOPPED', true, false)).toBe(false)
  })
  it('Neustart ⇔ operator (any state)', () => {
    expect(lifecycleControlEnabled('restart', 'RUNNING', true, false)).toBe(true)
    expect(lifecycleControlEnabled('restart', 'STOPPED', true, false)).toBe(true)
  })
  it('a non-operator or an in-flight request disables every control', () => {
    for (const a of ['start', 'stop', 'restart'] as const) {
      expect(lifecycleControlEnabled(a, 'STOPPED', false, false), `${a} non-operator`).toBe(false)
      expect(lifecycleControlEnabled(a, 'STOPPED', true, true), `${a} pending`).toBe(false)
    }
  })
})

describe('lifecycleRejectMessage (CYP-445 §6 — distinct 409/503, separate from CYP-421 ERROR reason)', () => {
  it('409 = conflict/transition, 503 = unavailable, else generic', () => {
    expect(lifecycleRejectMessage(new RestError(409, 'POST', '/api/agents/x/start', ''))).toContain('Übergang')
    expect(lifecycleRejectMessage(new RestError(503, 'POST', '/api/agents/x/start', ''))).toContain('nicht verfügbar')
    expect(lifecycleRejectMessage(new Error('network'))).toContain('fehlgeschlagen')
  })
})
