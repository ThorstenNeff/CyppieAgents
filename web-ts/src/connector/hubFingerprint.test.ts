import { describe, it, expect } from 'vitest'
import { deriveHubFingerprint, decodeHubKey, pgpWordTablesChecksum } from './hubFingerprint'

// CYP-798 — MY reproduction teeth (Team-2 build the independent cross-lens parity harness against the same golden
// vectors; no duplicate). These pin that the TS port reproduces the Kotlin golden vectors byte-for-byte + the
// word-table checksum RECOMPUTED over the TS tables (a ported-word drift must RED) + the fail-closed ≠32B path.

const PGP_LIST_SHA256 = 'e9b7b0052233a74aa56724ed3f79c272036aed68d8f4c0856e833c8043b9ac62'

interface Vector {
  name: string
  b64: string
  hex: string
  indices: number[]
  words: string[]
  qr: string
}

const POSITIVE: Vector[] = [
  {
    name: 'zeros32', b64: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    hex: '66:68:7a:ad:f8:62:bd:77:6c:8f:c1:8b:8e:9f:8e:20:08:97:14:85:6e:e2:33:b3:90:2a:59:1d:0d:5f:29:25',
    indices: [102, 104, 122, 173, 248, 98, 189, 119, 108, 143, 193],
    words: ['framework', 'gravity', 'keyboard', 'perceptive', 'Vulcan', 'gadgetry', 'skullcap', 'inception', 'glucose', 'midsummer', 'snapline'],
    qr: 'cyppie-hub-key:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
  },
  {
    name: 'seq_0..31', b64: 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
    hex: '63:0d:cd:29:66:c4:33:66:91:12:54:48:bb:b2:5b:4f:f4:12:a4:9c:73:2d:b2:c8:ab:c1:b8:58:1b:d7:10:dd',
    indices: [99, 13, 205, 41, 102, 196, 51, 102, 145, 18, 84],
    words: ['flatfoot', 'asteroid', 'spindle', 'certify', 'framework', 'reproduce', 'chisel', 'gossamer', 'pheasant', 'backwater', 'eating'],
    qr: 'cyppie-hub-key:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
  },
  {
    name: 'mul7', b64: 'AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tk=',
    hex: '2d:fd:60:2a:7a:26:0b:7a:12:90:5f:d2:eb:d4:b9:ac:f4:9e:ed:56:17:58:b9:cb:89:cb:cc:ee:38:9a:e0:2d',
    indices: [45, 253, 96, 42, 122, 38, 11, 122, 18, 144, 95],
    words: ['button', 'Wyoming', 'facial', 'chambermaid', 'keyboard', 'caretaker', 'alone', 'infancy', 'atlas', 'millionaire', 'eyetooth'],
    qr: 'cyppie-hub-key:AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tk=',
  },
  {
    name: 'mul5p1', b64: 'AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90eX6DiI2Sl5w=',
    hex: '94:12:3b:b2:2d:3f:a8:51:5c:d2:07:1b:f0:16:30:88:16:55:6d:d4:50:8f:01:bf:18:b5:cc:65:b3:c5:e7:8a',
    indices: [148, 18, 59, 178, 45, 63, 168, 81, 92, 210, 7],
    words: ['Pluto', 'backwater', 'clockwork', 'pioneer', 'button', 'customer', 'retouch', 'enchanting', 'escape', 'sensation', 'ahead'],
    qr: 'cyppie-hub-key:AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90eX6DiI2Sl5w=',
  },
  {
    name: 'all_0x01', b64: 'AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=',
    hex: '72:cd:6e:84:22:c4:07:fb:6d:09:86:90:f1:13:0b:7d:ed:7e:c2:f7:f5:e1:d3:0b:d9:d5:21:f0:15:36:37:93',
    indices: [114, 205, 110, 132, 34, 196, 7, 251, 109, 9, 134],
    words: ['highchair', 'sandalwood', 'goldfish', 'Jupiter', 'blockade', 'reproduce', 'ahead', 'Wichita', 'goggles', 'applicant', 'necklace'],
    qr: 'cyppie-hub-key:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=',
  },
  {
    name: 'all_0xFF', b64: '//////////////////////////////////////////8=',
    hex: 'af:96:13:76:0f:72:63:5f:bd:b4:4a:5a:0a:63:c3:9f:12:af:30:f9:50:a6:ee:5c:97:1b:e1:88:e8:9c:40:51',
    indices: [175, 150, 19, 118, 15, 114, 99, 95, 189, 180, 74],
    words: ['rocker', 'monument', 'Aztec', 'impetus', 'artist', 'holiness', 'flatfoot', 'forever', 'skullcap', 'politeness', 'dogsled'],
    qr: 'cyppie-hub-key://////////////////////////////////////////8=',
  },
]

// ≠32B / non-base64 / empty → null (an UPSTREAM error, NOT a trust reject — CYP-798 §4b).
const NEGATIVE = [
  { name: 'too_short_31B', b64: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==' },
  { name: 'too_long_33B', b64: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' },
  { name: 'not_base64', b64: 'not-base64!!!' },
  { name: 'empty', b64: '' },
]

describe('hubFingerprint (CYP-798 — byte-identical OOB derivation)', () => {
  it('reproduces the 6 positive golden vectors byte-for-byte', async () => {
    for (const v of POSITIVE) {
      const fp = await deriveHubFingerprint(v.b64)
      expect(fp, v.name).not.toBeNull()
      expect(fp!.indices, `${v.name} indices`).toEqual(v.indices)
      expect(fp!.words, `${v.name} words`).toEqual(v.words)
      expect(fp!.hex, `${v.name} hex`).toBe(v.hex)
      expect(fp!.qrPayload, `${v.name} qr`).toBe(v.qr)
    }
  })

  it('fail-closes ≠32B / non-base64 / empty / null to null (upstream error, not a reject)', async () => {
    for (const n of NEGATIVE) {
      expect(await deriveHubFingerprint(n.b64), `${n.name} → null`).toBeNull()
      expect(decodeHubKey(n.b64), `${n.name} decode → null`).toBeNull()
    }
    expect(await deriveHubFingerprint(null)).toBeNull()
    expect(await deriveHubFingerprint(undefined)).toBeNull()
    // Positive control: a valid 32-byte key decodes to 32 bytes (the guard is size-exact, not always-null).
    expect(decodeHubKey('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=')?.length).toBe(32)
  })

  it('recomputes the PGP word-table checksum over the TS tables == the :core pin (drift guard)', async () => {
    // NOT the copied literal — recomputed over THESE ported tables, so a single diverging word REDs here.
    expect(await pgpWordTablesChecksum()).toBe(PGP_LIST_SHA256)
  })
})
