import { describe, it, expect } from 'vitest'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { deriveHubFingerprint, pgpWordTablesChecksum } from './hubFingerprint'

// CYP-798 — cross-side parity fixture: the LIVE web-ts derivation, committed at
// app/shared/src/jvmTest/resources/cyp798-ts-derived.json for the Testers' Kotlin jvmTest to load and compare LIVE
// against HubFingerprintDisplay (live-Kotlin ≡ live-TS as ONE assertion → catches a co-drift that each-side-vs-frozen
// -golden would miss). The fixture is my ACTUAL TS output, not re-derived from the golden JSON.
//
// PO/Testers orchestration: committed fixture + a Dev-side FRESHNESS GUARD (this test). The guard REGENERATES the emit
// in-memory and asserts it is BYTE-EQUAL to the committed file → REDs if I change the TS derivation and forget to
// regenerate (kills the stale-fixture false-green: TS drifts, fixture doesn't → Testers would compare OLD TS). No
// cross-toolchain gate sequencing needed — the committed bytes ARE the current live-TS provenance.
//
// To deliberately regenerate after an intentional derivation change: `CYP798_WRITE_FIXTURE=1 npm test` (writes, then
// the same byte-equal assertion confirms the write). A plain `npm test` NEVER writes — it only verifies freshness.

const FIXTURE_PATH = resolve(process.cwd(), '../app/shared/src/jvmTest/resources/cyp798-ts-derived.json')

const POSITIVE_INPUTS: Record<string, string> = {
  zeros32: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
  'seq_0..31': 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
  mul7: 'AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tk=',
  mul5p1: 'AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90eX6DiI2Sl5w=',
  all_0x01: 'AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=',
  all_0xFF: '//////////////////////////////////////////8=',
}

const NEGATIVE_INPUTS: Record<string, string> = {
  too_short_31B: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==',
  too_long_33B: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  not_base64: 'not-base64!!!',
  empty: '',
}

async function buildFixtureBytes(): Promise<string> {
  const positive: Record<string, unknown> = {}
  for (const [name, b64] of Object.entries(POSITIVE_INPUTS)) {
    const fp = await deriveHubFingerprint(b64)
    expect(fp, name).not.toBeNull()
    positive[name] = { indices: fp!.indices, words: fp!.words, hexColon: fp!.hex, qrPayload: fp!.qrPayload }
  }
  const negative: Record<string, null> = {}
  for (const [name, b64] of Object.entries(NEGATIVE_INPUTS)) {
    expect(await deriveHubFingerprint(b64), name).toBeNull()
    negative[name] = null
  }
  const fixture = {
    _note:
      'GENERATED from the live web-ts derivation (CYP-798). Do NOT hand-edit. The Testers load this in a Kotlin ' +
      'jvmTest and compare it LIVE against HubFingerprintDisplay (cross-side TS≡Kotlin, co-drift guard). ' +
      'Regenerate with `CYP798_WRITE_FIXTURE=1 npm test`; freshness-guarded by parityFixtureFreshness.test.ts.',
    pgpListSha256: await pgpWordTablesChecksum(),
    positive,
    negative,
  }
  return JSON.stringify(fixture, null, 2) + '\n'
}

describe('CYP-798 parity fixture freshness guard (committed == current live-TS)', () => {
  it('the committed cyp798-ts-derived.json is byte-equal to the freshly regenerated live-TS output', async () => {
    const fresh = await buildFixtureBytes()

    if (process.env.CYP798_WRITE_FIXTURE === '1') {
      mkdirSync(dirname(FIXTURE_PATH), { recursive: true })
      writeFileSync(FIXTURE_PATH, fresh)
    }

    const committed = readFileSync(FIXTURE_PATH, 'utf8')
    // Byte-equal: any TS-derivation drift (word table, algorithm, QR/hex form) that was not re-committed REDs here.
    expect(committed).toBe(fresh)
  })
})
