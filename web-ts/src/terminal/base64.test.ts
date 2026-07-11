import { describe, it, expect } from 'vitest'
import { bytesToBase64, base64ToBytes, stringToBase64 } from './base64'

describe('base64 (CYP-405 terminal byte frames)', () => {
  it('round-trips arbitrary bytes, including control/VT and high bytes', () => {
    const bytes = new Uint8Array([0x1b, 0x5b, 0x32, 0x4a, 0x00, 0xff, 0x0a, 0x0d])
    expect(Array.from(base64ToBytes(bytesToBase64(bytes)))).toEqual(Array.from(bytes))
  })

  it('encodes/decodes a known value', () => {
    expect(bytesToBase64(new Uint8Array([104, 105]))).toBe('aGk=') // "hi"
    expect(Array.from(base64ToBytes('aGk='))).toEqual([104, 105])
  })

  it('stringToBase64 encodes UTF-8 and round-trips back to the original string', () => {
    const s = 'ls -la ✓ ü \n'
    expect(new TextDecoder().decode(base64ToBytes(stringToBase64(s)))).toBe(s)
  })
})
