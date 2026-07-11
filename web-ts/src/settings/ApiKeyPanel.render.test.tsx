// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup, waitFor } from '@testing-library/react'
import { ApiKeyPanel, type ApiKeyPanelProps } from './ApiKeyPanel'

afterEach(cleanup)

const base = (over: Partial<ApiKeyPanelProps> = {}): ApiKeyPanelProps => ({
  view: { set: true, masked: '***last4' },
  operator: true,
  onSave: vi.fn().mockResolvedValue(undefined),
  ...over,
})

const SECRET = 'sk-SUPER-SECRET-abcd'

describe('ApiKeyPanel (CYP-433 — leak-most-sensitive; the 8 spec teeth)', () => {
  it('§6.1 the stored key shows ONLY masked; the input is empty at load (no plaintext in the DOM)', () => {
    const { getByTestId } = render(<ApiKeyPanel {...base()} />)
    expect(getByTestId('settings.apiKey.masked').textContent).toBe('Hinterlegt: ***last4')
    expect((getByTestId('settings.apiKey.input') as HTMLInputElement).value).toBe('') // never preloaded
    expect((getByTestId('settings.apiKey.input') as HTMLInputElement).type).toBe('password') // masked by default
  })

  it('§6.2 the masked text is the SERVER value verbatim (client never masks a plaintext)', () => {
    const { getByTestId } = render(<ApiKeyPanel {...base({ view: { set: true, masked: '***server9' } })} />)
    expect(getByTestId('settings.apiKey.masked').textContent).toContain('***server9')
    cleanup()
    const unset = render(<ApiKeyPanel {...base({ view: { set: false } })} />)
    expect(unset.getByTestId('settings.apiKey.masked').textContent).toBe('Kein Schlüssel hinterlegt')
  })

  it('§6.3 reveal un-masks ONLY the input, never the stored status', () => {
    const { getByTestId } = render(<ApiKeyPanel {...base()} />)
    const input = () => getByTestId('settings.apiKey.input') as HTMLInputElement
    fireEvent.click(getByTestId('settings.apiKey.reveal'))
    expect(input().type).toBe('text') // the input un-masks
    expect(getByTestId('settings.apiKey.masked').textContent).toBe('Hinterlegt: ***last4') // status unchanged
  })

  it('§6.4 the field is cleared after a successful save (the typed key does not linger)', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const { getByTestId } = render(<ApiKeyPanel {...base({ onSave })} />)
    const input = getByTestId('settings.apiKey.input') as HTMLInputElement
    fireEvent.change(input, { target: { value: SECRET } })
    fireEvent.click(getByTestId('settings.apiKey.save'))
    expect(onSave).toHaveBeenCalledWith(SECRET)
    await waitFor(() => expect(input.value).toBe('')) // clear-after-save
  })

  it('§6.5 a save failure shows a GENERIC error — never the value, and the value stays out of the DOM text', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('boom'))
    const { getByTestId, findByTestId } = render(<ApiKeyPanel {...base({ onSave })} />)
    fireEvent.change(getByTestId('settings.apiKey.input'), { target: { value: SECRET } })
    fireEvent.click(getByTestId('settings.apiKey.save'))
    const err = await findByTestId('settings.apiKey.error')
    expect(err.textContent).toBe('Speichern fehlgeschlagen.')
    expect(err.textContent).not.toContain(SECRET)
    expect(document.body.textContent).not.toContain(SECRET) // never a text node
  })

  it('the typed plaintext is never a text node / never reflected into data-*/aria-*/title (only the input value)', () => {
    const { getByTestId } = render(<ApiKeyPanel {...base()} />)
    const input = getByTestId('settings.apiKey.input') as HTMLInputElement
    fireEvent.change(input, { target: { value: SECRET } })
    expect(input.value).toBe(SECRET) // transient, in the value only
    expect(document.body.textContent).not.toContain(SECRET) // NOT rendered as text anywhere
    // not mirrored into any attribute (title/aria/data)
    expect(input.getAttribute('title')).toBeNull()
    for (const a of Array.from(input.attributes)) {
      if (a.name !== 'value') expect(a.value).not.toContain(SECRET)
    }
  })

  it('§6.6 the effect hint is the AMBER "saved ≠ active" copy, never a green success/"aktiv"', async () => {
    const { getByTestId, findByTestId } = render(<ApiKeyPanel {...base()} />)
    fireEvent.change(getByTestId('settings.apiKey.input'), { target: { value: SECRET } })
    fireEvent.click(getByTestId('settings.apiKey.save'))
    const hint = await findByTestId('settings.apiKey.effectHint')
    expect(hint.textContent).toContain('nächsten Start') // saved ≠ active
    expect(hint.textContent).toContain('neu starten')
    expect(hint.textContent).not.toMatch(/aktiv|erfolgreich/i) // never "active"/green-success wording
    expect(hint.className).toContain('apikey-effect-hint') // amber tone (see index.css), not success
  })

  it('§6.7 there is NO restart control here — the hint points at the P2-a restart', async () => {
    const { getByTestId, queryByTestId, findByTestId } = render(<ApiKeyPanel {...base()} />)
    fireEvent.change(getByTestId('settings.apiKey.input'), { target: { value: SECRET } })
    fireEvent.click(getByTestId('settings.apiKey.save'))
    await findByTestId('settings.apiKey.effectHint')
    // only reveal + save buttons live here; no restart affordance
    expect(queryByTestId('settings.apiKey.restart')).toBeNull()
    expect(document.querySelectorAll('button').length).toBe(2)
  })

  it('§6.8 gate is present-but-disabled for a non-operator (NOT omitted), with the gate hint', () => {
    const onSave = vi.fn()
    const { getByTestId } = render(<ApiKeyPanel {...base({ operator: false, onSave })} />)
    expect(getByTestId('settings.section.apiKey')).toBeTruthy() // present, not omitted
    expect(getByTestId('settings.apiKey.masked').textContent).toBe('Hinterlegt: ***last4') // masked still shown (not secret)
    expect((getByTestId('settings.apiKey.input') as HTMLInputElement).disabled).toBe(true)
    expect((getByTestId('settings.apiKey.reveal') as HTMLButtonElement).disabled).toBe(true)
    expect((getByTestId('settings.apiKey.save') as HTMLButtonElement).disabled).toBe(true)
    expect(getByTestId('settings.apiKey.gateHint')).toBeTruthy()
  })
})
